package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.infra.fila.TarefaRecorrente;
import me.clama.pagamento.interno.relatorio.AuditoriaDoLedger;
import me.clama.pagamento.interno.relatorio.RelatorioDeRepasse;
import me.clama.pagamento.interno.relatorio.RelatorioMensal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Tarefa mensal do repasse (D6): alvo vindo do banco, isolamento por instituicao, upsert, auditoria que lanca e e-mail sem PII.
 * O relogio marca o dia 28 as 10:00 em Sao Paulo (ciclo 28/10 a 27/11/2026).
 */
class RelatorioMensalTest extends PagamentoAdminBase {

	static final String DIA_28 = "2026-11-28T13:00:00Z";
	static final String DENTRO = "2026-11-05T15:00:00Z";

	@Autowired RelatorioMensal tarefa;
	@Autowired AlertaAoAdmin.Props props;
	@MockitoSpyBean RelatorioDeRepasse relatorio;
	@MockitoSpyBean AuditoriaDoLedger auditoria;

	@BeforeEach
	void relogioNoDia28() {
		relogioEm(DIA_28);
	}

	/** Doacao paga de {@code nome} para a instituicao, com repasse no instante dado. */
	void doacao(String nome, Long inst, String status, String criadoEm, long mp) {
		var p = pedido(nome, 3000, inst);
		paga(p, mp);
		repasseEm(p, inst, status, criadoEm);
	}

	String email() {
		var enviados = caixa.para(props.emailAdmin());
		assertThat(enviados).hasSize(1);
		var e = enviados.get(0).email();
		return e.assunto() + "\n" + e.texto() + "\n" + e.html();
	}

	String texto() {
		return caixa.para(props.emailAdmin()).get(0).email().texto();
	}

	@Test
	void alvoESoAsAtivasMaisAInativaComRepasseNoCiclo() {
		var a = instituicao("Ativa Com Doacao");
		instituicao("Ativa Sem Doacao");
		var inativaComRepasse = instituicao("Inativa Com Repasse");
		var inativaSemRepasse = instituicao("Inativa Sem Repasse");
		jdbc.update("update instituicao set ativo = false where id in (?, ?)", inativaComRepasse, inativaSemRepasse);
		doacao("Maria Silva", a, "REGISTRADO", DENTRO, 1);
		doacao("Joao Souza", inativaComRepasse, "TRANSFERIDO", DENTRO, 2);
		var cancelada = instituicao("Inativa So Cancelado");
		jdbc.update("update instituicao set ativo = false where id = ?", cancelada);
		doacao("Ana Lima", cancelada, "CANCELADO", DENTRO, 3);

		tarefa.rodar(false, null);

		assertThat(contar("select count(*) from relatorio_repasse")).isEqualTo(3);
		assertThat(contar("select count(*) from relatorio_repasse where instituicao_id = ?", inativaSemRepasse)).isZero();
		assertThat(contar("select count(*) from relatorio_repasse where instituicao_id = ?", cancelada)).isZero();
		var e = email();
		assertThat(e).contains("Ativa Com Doacao: 1 doação, R$ 6,00 a repassar", "Ativa Sem Doacao: 0 doações, R$ 0,00 a repassar",
				"Inativa Com Repasse: 1 doação, R$ 6,00 a repassar").doesNotContain("Inativa Sem Repasse", "Inativa So Cancelado");
		var linha = jdbc.queryForMap("select * from relatorio_repasse where instituicao_id = ?", a);
		assertThat(linha.get("de").toString()).isEqualTo("2026-10-28");
		assertThat(linha.get("ate").toString()).isEqualTo("2026-11-27");
		assertThat(((Number) linha.get("total_repasse_centavos")).longValue()).isEqualTo(600);
		assertThat(((Number) linha.get("pendente_centavos")).longValue()).isEqualTo(600);
	}

	@Test
	void instituicaoQueLancaNaoImpedeAsOutrasEAExecucaoFicaNaoOkDepoisDoEmail() {
		var a = instituicao("Lar A");
		var b = instituicao("Lar B");
		var c = instituicao("Lar C");
		doacao("Maria Silva", a, "REGISTRADO", DENTRO, 1);
		doacao("Joao Souza", b, "REGISTRADO", DENTRO, 2);
		doacao("Ana Lima", c, "REGISTRADO", DENTRO, 3);
		doThrow(new IllegalStateException("segredo@exemplo.com")).when(relatorio).previa(eq(b), any());

		assertThatThrownBy(() -> tarefa.executeRecurringly(null, null)).isInstanceOf(TarefaRecorrente.FalhaParcial.class)
				.hasMessage("2 de 3 instituições, faltou: Lar B");

		assertThat(jdbc.queryForList("select instituicao_id from relatorio_repasse order by 1", Long.class)).containsExactly(a, c);
		var e = email(); // o e-mail saiu antes de lancar
		assertThat(e).contains("Faltou: Lar B", "Lar A: 1 doação", "Lar C: 1 doação", ": atenção").doesNotContain("segredo");
		var x = jdbc.queryForMap("select ok, resumo, erro, simulacao, manual from tarefa_execucao where tarefa = 'relatorio-repasse-mensal'");
		assertThat(x.get("ok")).isEqualTo(false);
		assertThat(x.get("resumo")).isEqualTo("2 de 3 instituições, faltou: Lar B");
		assertThat(x.get("erro")).isEqualTo(TarefaRecorrente.FalhaParcial.class.getName());
	}

	@Test
	void auditoriaQueLancaViraNaoRodouNuncaCritico() {
		var a = instituicao("Lar A");
		doacao("Maria Silva", a, "REGISTRADO", DENTRO, 1);
		doThrow(new IllegalStateException("rede caiu")).when(auditoria).auditar();

		tarefa.rodar(false, null);

		var e = email();
		assertThat(e).contains("A auditoria não rodou (erro técnico; veja Tarefas).", ": atenção").doesNotContain("CRÍTIC", "crítico", "Ledger consistente");
		assertThat(contar("select count(*) from relatorio_repasse")).isEqualTo(1); // o relatorio nao depende da auditoria
		assertThat(jdbc.queryForObject("select ok from tarefa_execucao where tarefa = 'relatorio-repasse-mensal'", Boolean.class)).isTrue();
	}

	@Test
	void criticoRealApareceComOValorEOLedgerOkQuandoConsistente() {
		var a = instituicao("Lar A");
		doacao("Maria Silva", a, "REGISTRADO", DENTRO, 1);
		tarefa.rodar(false, null);
		assertThat(email()).contains("Ledger consistente.", ": relatórios prontos").doesNotContain("CRÍTIC");
		caixa.limpar();

		var orfao = pedido("Joao Souza", 3001, a);
		paga(orfao, 2); // pago sem linha no ledger: orfao, R$ 6,00 em jogo
		tarefa.rodar(false, null);
		assertThat(email()).contains("Ledger com 1 achado(s) CRÍTICO(S), R$ 6,00 em jogo.", "orfao", ": atenção");
	}

	@Test
	void estornosAAcertarDoCicloSaoListados() {
		var a = instituicao("Lar A");
		var transferido = pedido("Maria Silva", 3000, a);
		paga(transferido, 1);
		repasseEm(transferido, a, "TRANSFERIDO", DENTRO);
		var parcial = pedido("Joao Souza", 3000, a);
		paga(parcial, 2);
		repasseEm(parcial, a, "REGISTRADO", DENTRO);
		var antigo = pedido("Ana Lima", 3000, a);
		paga(antigo, 3);
		repasseEm(antigo, a, "TRANSFERIDO", "2026-09-05T15:00:00Z");
		var e1 = evento("e1", "PROCESSADO", "repasse_transferido");
		var e2 = evento("e2", "IGNORADO", "estorno_parcial");
		var e3 = evento("e3", "PROCESSADO", "repasse_transferido");
		jdbc.update("update pagamento_evento set pedido_id = ?, atualizado_em = ?::timestamptz where id = ?", transferido, DENTRO, e1);
		jdbc.update("update pagamento_evento set pedido_id = ?, atualizado_em = ?::timestamptz where id = ?", parcial, DENTRO, e2);
		jdbc.update("update pagamento_evento set pedido_id = ?, atualizado_em = ?::timestamptz where id = ?", antigo, "2026-09-05T15:00:00Z", e3);

		tarefa.rodar(false, null);

		var t = texto();
		var estornos = t.substring(t.indexOf("Estornos a acertar:"));
		assertThat(estornos).contains("estorno depois de transferido, Lar A, pedido #", "estorno parcial, Lar A, pedido #", "repasse de R$ 6,00");
		assertThat(estornos.lines().filter(l -> l.startsWith("- "))).hasSize(2); // o de setembro e de outro ciclo
	}

	@Test
	void emailSemNomeNemEmailDeDoadorSemIdDePedidoComLinkEChaveDoCiclo() {
		var a = instituicao("Lar A");
		var p = pedido("Maria Silva Santos", 3000, a);
		paga(p, 1);
		repasseEm(p, a, "REGISTRADO", DENTRO);

		tarefa.rodar(false, null);

		var enviado = caixa.para(props.emailAdmin()).get(0);
		assertThat(enviado.chave()).isEqualTo("relatorio-repasse/2026-11-27");
		assertThat(enviado.email().assunto()).isEqualTo("Repasse do ciclo 28/10/2026 a 27/11/2026: relatórios prontos");
		var tudo = email();
		assertThat(tudo).doesNotContain("Maria", "Santos", "maria@", "exemplo.com", "52998224725", "Pela família", p.toString())
				.contains(props.frontendUrl().replaceAll("/+$", "") + "/admin/relatorios");
		// sem anexo: o e-mail so tem texto e html
		assertThat(java.util.Arrays.stream(me.clama.infra.email.Email.class.getRecordComponents()).map(c -> c.getName()).toList())
				.containsExactly("para", "assunto", "texto", "html");
	}

	@Test
	void zeroDoacoesAindaEnviaOEmail() {
		instituicao("Lar A");
		tarefa.rodar(false, null);
		assertThat(email()).contains("Lar A: 0 doações, R$ 0,00 a repassar", ": relatórios prontos");
		assertThat(contar("select count(*) from relatorio_repasse")).isEqualTo(1);
		assertThat(contar("select count(*) from tarefa_execucao where tarefa = 'relatorio-repasse-mensal' and ok")).isEqualTo(1);
	}

	@Test
	void semNenhumaInstituicaoTambemEnvia() {
		tarefa.rodar(false, null);
		assertThat(email()).contains("Nenhuma instituição.");
	}

	@Test
	void dryRunNaoGravaNemEnviaESoDevolveOResumo() {
		var a = instituicao("Lar A");
		doacao("Maria Silva", a, "REGISTRADO", DENTRO, 1);

		tarefa.rodar(true, 7L);

		assertThat(contar("select count(*) from relatorio_repasse")).isZero();
		assertThat(caixa.enviados()).isEmpty();
		var resumo = jdbc.queryForObject("select resumo from tarefa_execucao where tarefa = 'relatorio-repasse-mensal'", String.class);
		assertThat(resumo).isEqualTo("ciclo 28/10/2026 a 27/11/2026: 1 de 1 instituições, R$ 6,00 a repassar; ledger ok (simulação: nada gravado nem enviado)");
	}

	@Test
	void rodarDuasVezesNoMesmoCicloDeixaUmaLinhaPorInstituicaoComOTotalNovo() {
		var a = instituicao("Lar A");
		var b = instituicao("Lar B");
		doacao("Maria Silva", a, "REGISTRADO", DENTRO, 1);
		tarefa.rodar(false, null);
		doacao("Joao Souza", a, "REGISTRADO", DENTRO, 2);
		tarefa.rodar(false, null);

		assertThat(contar("select count(*) from relatorio_repasse")).isEqualTo(2);
		assertThat(jdbc.queryForObject("select quantidade from relatorio_repasse where instituicao_id = ?", Integer.class, a)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select quantidade from relatorio_repasse where instituicao_id = ?", Integer.class, b)).isZero();
		assertThat(caixa.enviados()).hasSize(2); // a chave do ciclo deixa a Resend descartar o repetido
		assertThat(caixa.enviados().stream().map(x -> x.chave()).distinct().toList()).containsExactly("relatorio-repasse/2026-11-27");
	}

	@Test
	void rotaListaOsResumosGuardadosEMostraSeOTotalMudou() throws Exception {
		var a = instituicao("Lar A");
		var p = pedido("Maria Silva", 3000, a);
		paga(p, 1);
		var repasse = repasseEm(p, a, "REGISTRADO", DENTRO);
		tarefa.rodar(false, null);
		relogio.zerar();

		var adm = entrar(Papel.ADMIN);
		var antes = json(adm.get("/api/admin/repasses/relatorios-mensais"));
		assertThat(antes.get("page").get("totalElements").asInt()).isEqualTo(1);
		var r = antes.get("content").get(0);
		assertThat(r.get("instituicao").asString()).isEqualTo("Lar A");
		assertThat(r.get("de").asString()).isEqualTo("2026-10-28");
		assertThat(r.get("ate").asString()).isEqualTo("2026-11-27");
		assertThat(r.get("quantidade").asInt()).isEqualTo(1);
		assertThat(r.get("totalDoadoCentavos").asInt()).isEqualTo(3000);
		assertThat(r.get("totalRepasseCentavos").asInt()).isEqualTo(600);
		assertThat(r.get("pendenteCentavos").asInt()).isEqualTo(600);
		assertThat(r.get("totalAgoraCentavos").asInt()).isEqualTo(600);
		assertThat(r.get("geradoEm").asString()).endsWith("Z");

		jdbc.update("update repasse set status = 'CANCELADO', cancelado_em = now() where id = ?", repasse); // estorno depois da geracao
		var depois = json(adm.get("/api/admin/repasses/relatorios-mensais")).get("content").get(0);
		assertThat(depois.get("totalRepasseCentavos").asInt()).isEqualTo(600);
		assertThat(depois.get("totalAgoraCentavos").asInt()).isZero();
	}

	@Test
	void rotaPagina() throws Exception {
		for (int i = 0; i < 3; i++) instituicao("Lar " + i);
		tarefa.rodar(false, null);
		relogio.zerar();
		var adm = entrar(Papel.ADMIN);
		var pagina = json(adm.get("/api/admin/repasses/relatorios-mensais?size=2&page=1"));
		assertThat(pagina.get("content")).hasSize(1);
		assertThat(pagina.get("page").get("totalElements").asInt()).isEqualTo(3);
		assertThat(json(adm.get("/api/admin/repasses/relatorios-mensais")).get("content")).hasSize(3);
	}

	@Test
	void rotaExigeAdmin() throws Exception {
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu");
		assertThat(anonimo.get("/api/admin/repasses/relatorios-mensais").statusCode()).isEqualTo(401);
		assertThat(entrar(Papel.CLIENTE).get("/api/admin/repasses/relatorios-mensais").statusCode()).isEqualTo(403);
	}

	@Test
	void resumoDaExecucaoNaoTemNomeNemEmail() {
		var a = instituicao("Lar A");
		doacao("Maria Silva Santos", a, "REGISTRADO", DENTRO, 1);
		tarefa.rodar(false, null);
		var resumo = jdbc.queryForObject("select resumo from tarefa_execucao where tarefa = 'relatorio-repasse-mensal'", String.class);
		assertThat(List.of(resumo)).noneMatch(r -> r.contains("Maria") || r.contains("@"));
	}
}
