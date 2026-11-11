package me.clama.infra.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** Tela de tarefas (D9) com o scheduler parado: o que a tela mostra e o que o "Rodar agora" enfileira, sem ninguem pegar o job. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, TarefasAdminTest.Tarefas.class })
@ActiveProfiles("test")
class TarefasAdminTest {

	static final String OK = "tarefa-teste-ok";
	static final String FALHA = "tarefa-teste-falha";

	/** Tarefa programavel: o resumo mostra o modo em que rodou. */
	static class Teste extends TarefaRecorrente {
		final Function<Boolean, String> corpo;

		Teste(String nome, Function<Boolean, String> corpo) {
			super(nome, "0 0 4 1 1 *");
			this.corpo = corpo;
		}

		@Override
		protected String executar(boolean dryRun) {
			return corpo.apply(dryRun);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Tarefas {
		@Bean
		Teste tarefaOk() {
			return new Teste(OK, dry -> "fez 3 coisas (" + (dry ? "simulacao" : "real") + ")");
		}

		@Bean
		Teste tarefaFalha() {
			return new Teste(FALHA, dry -> {
				throw new IllegalStateException("segredo@exemplo.com");
			});
		}
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired JobRodarTarefa job;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from tarefa_execucao where tarefa like 'tarefa-teste-%'");
		jdbc.update("delete from auditoria where entidade = 'tarefa'");
		jdbc.update("delete from usuario where email like 'tarefas-%@exemplo.com'");
	}

	Navegador entrar(Papel papel) throws Exception {
		var email = "tarefas-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", papel);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	void agendar(String nome, String quando) {
		jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, version) values (?, 'recurring', "
				+ quando + ", false, 1)", nome);
	}

	JsonNode tarefa(Navegador nav, String nome) throws Exception {
		for (var t : Navegador.json(nav.get("/api/admin/tarefas"))) if (t.get("nome").asString().equals(nome)) return t;
		throw new AssertionError("a tela nao lista " + nome);
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu");
		for (var nav : List.of(anonimo, entrar(Papel.CLIENTE))) {
			var esperado = nav == anonimo ? 401 : 403;
			assertThat(nav.get("/api/admin/tarefas").statusCode()).isEqualTo(esperado);
			assertThat(nav.get("/api/admin/tarefas/" + OK + "/execucoes").statusCode()).isEqualTo(esperado);
			assertThat(nav.post("/api/admin/tarefas/" + OK + "/execucoes", "{}").statusCode()).isEqualTo(esperado);
		}
		assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class)).isZero();
	}

	@Test
	void listaAsSeisRecorrentesDeHojeComCronModoEProximoDisparo() throws Exception {
		agendar(OK, "now() + interval '1 hour'");
		var admin = entrar(Papel.ADMIN);
		var lista = Navegador.json(admin.get("/api/admin/tarefas"));
		var nomes = new java.util.ArrayList<String>();
		lista.forEach(t -> nomes.add(t.get("nome").asString()));
		assertThat(nomes).contains("pedidos-presos", "reconciliar-freemium", "reconciliar-espelho", "alerta-comentarios", "purgar-ips",
				"reprocessar-eventos-mp", OK, FALHA).isSorted();
		var ok = tarefa(admin, OK);
		assertThat(ok.get("cron").asString()).isEqualTo("0 0 4 1 1 *");
		assertThat(ok.get("simulacaoConfigurada").asBoolean()).isFalse();
		assertThat(ok.get("proximaExecucao").asString()).endsWith("Z");
		assertThat(ok.get("rodandoAgora").asBoolean()).isFalse();
		assertThat(ok.get("falhasSeguidas").asInt()).isZero();
		assertThat(ok.get("situacao").asString()).isEqualTo("ok");
		assertThat(ok.get("ultima").isNull()).isTrue();
		assertThat(tarefa(admin, "pedidos-presos").get("cron").asString()).isEqualTo("0 */10 * * * *");
	}

	@Test
	void semLinhaNoSchedulerENaoAgendada() throws Exception {
		var t = tarefa(entrar(Papel.ADMIN), OK);
		assertThat(t.get("situacao").asString()).isEqualTo("nao_agendada");
		assertThat(t.get("proximaExecucao").isNull()).isTrue();
	}

	@Test
	void horarioVencidoHaMaisDeCincoMinutosESemNinguemPegarEAtrasada() throws Exception {
		agendar(OK, "now() - interval '6 minutes'");
		agendar(FALHA, "now() - interval '4 minutes'"); // dentro da tolerancia
		var admin = entrar(Papel.ADMIN);
		assertThat(tarefa(admin, OK).get("situacao").asString()).isEqualTo("atrasada");
		assertThat(tarefa(admin, FALHA).get("situacao").asString()).isEqualTo("ok");
		jdbc.update("update scheduled_tasks set picked = true, picked_by = 'no' where task_name = ?", OK); // rodando agora nao e atrasada
		var rodando = tarefa(admin, OK);
		assertThat(rodando.get("situacao").asString()).isEqualTo("ok");
		assertThat(rodando.get("rodandoAgora").asBoolean()).isTrue();
	}

	@Test
	void rodarAgoraSemCorpoESimulacaoEOCliqueDuploRecebe409() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var fila = "select count(*) from scheduled_tasks where task_instance = 'rodar-tarefa/" + OK + "'";
		var auditorias = "select count(*) from auditoria where entidade = 'tarefa'";
		assertThat(admin.post("/api/admin/tarefas/" + OK + "/execucoes", "{}").statusCode()).isEqualTo(202);
		assertThat(jdbc.queryForObject(auditorias, Integer.class)).isZero(); // sem corpo = simulacao: nao audita
		jdbc.update(BancoDeTeste.LIMPAR_FILA);

		assertThat(admin.post("/api/admin/tarefas/" + OK + "/execucoes", "{\"simulacao\": false}").statusCode()).isEqualTo(202);
		assertThat(jdbc.queryForObject(auditorias, Integer.class)).isEqualTo(1);
		var segundo = admin.post("/api/admin/tarefas/" + OK + "/execucoes", "{\"simulacao\": false}");
		assertThat(segundo.statusCode()).isEqualTo(409);
		assertThat(Navegador.json(segundo).get("codigo").asString()).isEqualTo("tarefa_na_fila");
		assertThat(jdbc.queryForObject(fila, Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(auditorias, Integer.class)).isEqualTo(1); // o clique duplo nao audita de novo

		job.executar(new JobRodarTarefa.Disparo(OK, true, 7L), 0);
		var historico = Navegador.json(admin.get("/api/admin/tarefas/" + OK + "/execucoes"));
		assertThat(historico.get("page").get("totalElements").asInt()).isEqualTo(1);
		var e = historico.get("content").get(0);
		assertThat(e.get("ok").asBoolean()).isTrue();
		assertThat(e.get("simulacao").asBoolean()).isTrue();
		assertThat(e.get("manual").asBoolean()).isTrue();
		assertThat(e.get("autorId").asInt()).isEqualTo(7);
		assertThat(e.get("resumo").asString()).isEqualTo("fez 3 coisas (simulacao)");
		assertThat(tarefa(admin, OK).get("ultima").get("resumo").asString()).isEqualTo("fez 3 coisas (simulacao)");
	}

	/** Tarefa com {@code dry-run=true} dispara agendada (manual=false) e simulada: se lanca, e falha de verdade. */
	@Test
	void tarefaConfiguradaEmDryRunQueLancaMostraFalhou() throws Exception {
		agendar(OK, "now() + interval '1 hour'");
		jdbc.update("insert into tarefa_execucao (tarefa, inicio, fim, simulacao, manual, ok, erro) values (?, now(), now(), true, false, false, 'java.lang.IllegalStateException')", OK);
		var t = tarefa(entrar(Papel.ADMIN), OK);
		assertThat(t.get("situacao").asString()).isEqualTo("falhou");
		assertThat(t.get("ultima").get("manual").asBoolean()).isFalse();
	}

	@Test
	void simulacaoBemSucedidaNaoEscondeUmaFalhaReal() throws Exception {
		agendar(OK, "now() + interval '1 hour'");
		jdbc.update("insert into tarefa_execucao (tarefa, inicio, fim, simulacao, manual, ok, erro) values (?, now() - interval '2 hours', now() - interval '2 hours', false, false, false, 'java.lang.IllegalStateException')", OK);
		jdbc.update("insert into tarefa_execucao (tarefa, inicio, fim, simulacao, manual, ok, resumo) values (?, now() - interval '1 hour', now() - interval '1 hour', true, true, true, 'simulou')", OK);
		var t = tarefa(entrar(Papel.ADMIN), OK);
		assertThat(t.get("situacao").asString()).isEqualTo("falhou");
		assertThat(t.get("ultima").get("simulacao").asBoolean()).isTrue();
		assertThat(t.get("ultima").get("ok").asBoolean()).isTrue();
		jdbc.update("insert into tarefa_execucao (tarefa, inicio, fim, simulacao, manual, ok) values (?, now(), now(), false, false, true)", OK);
		assertThat(tarefa(entrar(Papel.ADMIN), OK).get("situacao").asString()).isEqualTo("ok"); // uma real depois conserta
	}

	@Test
	void rodarDeVerdadeGravaAuditoriaEASimulacaoNao() throws Exception {
		var admin = entrar(Papel.ADMIN);
		assertThat(admin.post("/api/admin/tarefas/" + OK + "/execucoes", "{\"simulacao\": true}").statusCode()).isEqualTo(202);
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'tarefa'", Integer.class)).isZero();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);

		assertThat(admin.post("/api/admin/tarefas/" + OK + "/execucoes", "{\"simulacao\": false}").statusCode()).isEqualTo(202);
		var a = jdbc.queryForMap("select autor_id, entidade_id, acao, diff::text as diff from auditoria where entidade = 'tarefa'");
		assertThat(a.get("entidade_id")).isEqualTo(OK);
		assertThat(a.get("acao")).isEqualTo("ADICAO");
		assertThat(a.get("autor_id")).isNotNull();
		assertThat(a.get("diff").toString()).contains("real");
	}

	@Test
	void tarefaQueLancaGravaOkFalsoComAClasseEJamaisAMensagem() throws Exception {
		var admin = entrar(Papel.ADMIN);
		agendar(FALHA, "now() + interval '1 hour'");
		job.executar(new JobRodarTarefa.Disparo(FALHA, false, 1L), 0); // manual: nao relanca
		var res = admin.get("/api/admin/tarefas/" + FALHA + "/execucoes");
		var e = Navegador.json(res).get("content").get(0);
		assertThat(e.get("ok").asBoolean()).isFalse();
		assertThat(e.get("erro").asString()).isEqualTo("java.lang.IllegalStateException");
		assertThat(e.get("resumo").isNull()).isTrue();
		assertThat(res.body()).doesNotContain("segredo");
		var t = tarefa(admin, FALHA);
		assertThat(t.get("situacao").asString()).isEqualTo("falhou");
		assertThat(t.get("ultima").get("erro").asString()).isEqualTo("java.lang.IllegalStateException");
	}

	@Test
	void historicoEPaginadoComAsMaisNovasPrimeiro() throws Exception {
		for (int i = 1; i <= 3; i++) {
			jdbc.update("insert into tarefa_execucao (tarefa, inicio, fim, simulacao, manual, ok, resumo) values (?, ?::timestamptz, ?::timestamptz, false, false, true, ?)",
					OK, Instant.parse("2026-11-0" + i + "T10:00:00Z").toString(), Instant.parse("2026-11-0" + i + "T10:00:01Z").toString(), "n" + i);
		}
		var admin = entrar(Papel.ADMIN);
		var p = Navegador.json(admin.get("/api/admin/tarefas/" + OK + "/execucoes?size=2&page=0"));
		assertThat(p.get("page").get("totalElements").asInt()).isEqualTo(3);
		assertThat(p.get("content").size()).isEqualTo(2);
		assertThat(p.get("content").get(0).get("resumo").asString()).isEqualTo("n3");
		assertThat(p.get("content").get(1).get("resumo").asString()).isEqualTo("n2");
	}

	@Test
	void nomeDesconhecidoRecebe404NasDuasRotas() throws Exception {
		var admin = entrar(Papel.ADMIN);
		assertThat(admin.get("/api/admin/tarefas/nao-existe/execucoes").statusCode()).isEqualTo(404);
		var r = admin.post("/api/admin/tarefas/nao-existe/execucoes", "{}");
		assertThat(r.statusCode()).isEqualTo(404);
		assertThat(Navegador.json(r).get("mensagemPastoral").asString()).isEqualTo("Essa tarefa não existe.");
		assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class)).isZero();
	}
}
