package me.clama.catalogo.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import me.clama.BancoDeTeste;
import me.clama.catalogo.interno.InstituicaoLogo;
import me.clama.catalogo.interno.InstituicaoLogoRepository;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class CatalogoAdminTest {

	static final String SENHA = "senha-forte-123";
	static final String DUPLICADO = "Já existe um plano ativo com este nome.";

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired JdbcClient jdbc;
	@Autowired InstituicaoLogoRepository logos;
	final List<Long> criados = new ArrayList<>();

	@BeforeEach
	@AfterEach
	void limpar() { // banco compartilhado: nao deixa linhas para outros testes
		limite.limpar();
		jdbc.sql("delete from instituicao_logo").update();
		jdbc.sql("delete from instituicao").update();
		jdbc.sql("delete from plano where tipo = 'COMUM'").update();
		jdbc.sql("update plano set nome = 'Gratuito', descricao = 'Sua primeira oração — por nossa conta' where tipo = 'GRATUITO'").update();
		jdbc.sql("update plano set nome = 'Livre', descricao = '' where tipo = 'VALOR_LIVRE'").update();
		jdbc.sql("delete from auditoria where entidade in ('plano', 'instituicao')").update();
		criados.forEach(id -> {
			jdbc.sql("delete from auditoria where autor_id = :id").param("id", id).update();
			jdbc.sql("delete from usuario where id = :id").param("id", id).update();
		});
		criados.clear();
	}

	private Navegador entrar(Papel papel) throws Exception {
		var u = Navegador.usuario(usuarios, encoder, emailUnico(), SENHA, papel);
		criados.add(u.getId());
		var nav = new Navegador(porta);
		assertThat(nav.login(u.getEmail(), SENHA).statusCode()).isEqualTo(200);
		return nav;
	}

	private static String plano(String nome, long valor) {
		return "{\"nome\":\"" + nome + "\",\"descricao\":\"d\",\"valorCentavos\":" + valor + "}";
	}

	private long id(java.net.http.HttpResponse<String> res) {
		return json(res).get("id").asLong();
	}

	private long auditorias(String entidade, long id, String acao) {
		return jdbc.sql("select count(*) from auditoria where entidade = :e and entidade_id = :id and acao = :a")
				.param("e", entidade).param("id", Long.toString(id)).param("a", acao).query(Long.class).single();
	}

	private long tecnico(String tipo) {
		return jdbc.sql("select id from plano where tipo = :t").param("t", tipo).query(Long.class).single();
	}

	// --- acesso ---

	@Test
	void anonimoRecebe401ClienteRecebe403() throws Exception {
		var anonimo = new Navegador(porta);
		var cliente = entrar(Papel.CLIENTE);
		for (var caminho : List.of("/api/admin/planos", "/api/admin/instituicoes")) {
			assertThat(anonimo.get(caminho).statusCode()).isEqualTo(401);
			assertThat(cliente.get(caminho).statusCode()).isEqualTo(403);
		}
		assertThat(cliente.post("/api/admin/planos", plano("X", 100)).statusCode()).isEqualTo(403);
		assertThat(cliente.post("/api/admin/instituicoes", "{\"nome\":\"X\"}").statusCode()).isEqualTo(403);
	}

	@Test
	void adminSemSerSuperusuarioEscreve() throws Exception {
		assertThat(entrar(Papel.ADMIN).post("/api/admin/planos", plano("Bom", 3000)).statusCode()).isEqualTo(201);
	}

	@Test
	void deleteDevolve405() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var p = id(admin.post("/api/admin/planos", plano("Bom", 3000)));
		var i = id(admin.post("/api/admin/instituicoes", "{\"nome\":\"Casa\"}"));
		assertThat(admin.delete("/api/admin/planos/" + p).statusCode()).isEqualTo(405);
		assertThat(admin.delete("/api/admin/instituicoes/" + i).statusCode()).isEqualTo(405);
	}

	// --- planos ---

	@Test
	void criaPlanoComumEListaComTipoETecnicos() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var res = admin.post("/api/admin/planos", "{\"nome\":\" Bom \",\"descricao\":\"d\",\"valorCentavos\":3000,\"complexidade\":\"COM_VERSICULO\",\"ordem\":3}");
		assertThat(res.statusCode()).isEqualTo(201);
		var p = json(res);
		assertThat(p.get("nome").asString()).isEqualTo("Bom");
		assertThat(p.get("tipo").asString()).isEqualTo("COMUM");
		assertThat(p.get("ativo").asBoolean()).isTrue();
		assertThat(p.get("complexidade").asString()).isEqualTo("COM_VERSICULO");
		assertThat(p.get("valorCentavos").asLong()).isEqualTo(3000);
		var lista = json(admin.get("/api/admin/planos"));
		assertThat(lista.size()).isEqualTo(3);
		assertThat(lista.get(0).get("ordem").asInt()).isEqualTo(3); // ordem, id: os tecnicos (1000) vao depois
		assertThat(lista.get(1).get("tipo").asString()).isEqualTo("GRATUITO");
		assertThat(admin.get("/api/admin/planos").headers().firstValue("Cache-Control").orElse("")).contains("no-store");
		assertThat(auditorias("plano", p.get("id").asLong(), "ADICAO")).isEqualTo(1);
	}

	@Test
	void entradaInvalidaDevolve400() throws Exception {
		var admin = entrar(Papel.ADMIN);
		for (var corpo : List.of(plano("X", 0), plano("X", -5), plano("", 100), plano("a".repeat(81), 100),
				"{\"nome\":\"X\",\"valorCentavos\":100,\"descricao\":\"" + "d".repeat(2001) + "\"}",
				"{\"nome\":\"X\",\"valorCentavos\":100,\"complexidade\":\"xyz\"}",
				"{\"nome\":\"X\",\"valorCentavos\":100,\"ordem\":-1}", "{\"nome\":\"X\"}")) {
			assertThat(admin.post("/api/admin/planos", corpo).statusCode()).as(corpo).isEqualTo(400);
		}
		var id = id(admin.post("/api/admin/planos", plano("Bom", 3000)));
		for (var corpo : List.of("{\"valorCentavos\":0}", "{\"valorCentavos\":-1}", "{\"nome\":\"   \"}", "{\"complexidade\":\"simples\"}")) {
			assertThat(admin.patch("/api/admin/planos/" + id, corpo).statusCode()).as(corpo).isEqualTo(400);
		}
	}

	@Test
	void patchMudaSoOQueVeioEAuditaODiff() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = id(admin.post("/api/admin/planos", plano("Bom", 3000)));
		var res = admin.patch("/api/admin/planos/" + id, "{\"valorCentavos\":4000,\"ativo\":false}");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("valorCentavos").asLong()).isEqualTo(4000);
		assertThat(json(res).get("ativo").asBoolean()).isFalse();
		assertThat(json(res).get("nome").asString()).isEqualTo("Bom");
		var diff = jdbc.sql("select diff::text from auditoria where entidade = 'plano' and entidade_id = :id and acao = 'MUDANCA'")
				.param("id", Long.toString(id)).query(String.class).single();
		var d = Navegador.JSON.readTree(diff);
		assertThat(d.propertyNames()).containsExactlyInAnyOrder("valorCentavos", "ativo");
		assertThat(d.get("valorCentavos").get("antes").asLong()).isEqualTo(3000);
		assertThat(d.get("valorCentavos").get("depois").asLong()).isEqualTo(4000);
		assertThat(admin.patch("/api/admin/planos/" + id, "{\"ativo\":true}").statusCode()).isEqualTo(200);
		assertThat(auditorias("plano", id, "MUDANCA")).isEqualTo(2);
	}

	@Test
	void patchSemMudancaNaoGravaAuditoria() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = id(admin.post("/api/admin/planos", plano("Bom", 3000)));
		assertThat(admin.patch("/api/admin/planos/" + id, "{}").statusCode()).isEqualTo(200);
		assertThat(admin.patch("/api/admin/planos/" + id, "{\"nome\":\"Bom\",\"valorCentavos\":3000,\"ativo\":true}").statusCode()).isEqualTo(200);
		assertThat(auditorias("plano", id, "MUDANCA")).isZero();
	}

	@Test
	void patchDePlanoInexistenteDevolve404() throws Exception {
		var res = entrar(Papel.ADMIN).patch("/api/admin/planos/999999", "{\"ativo\":false}");
		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(json(res).get("codigo").asString()).isEqualTo("nao_encontrado");
	}

	@Test
	void nomeDuplicadoEntreAtivosEh400ComErroNoCampoNome() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var a = id(admin.post("/api/admin/planos", plano("Bom", 3000)));
		var res = admin.post("/api/admin/planos", plano("Bom", 5000));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("nome");
		assertThat(json(res).get("erros").get(0).get("mensagem").asString()).isEqualTo(DUPLICADO);
		// inativo libera o nome; reativar com o nome ocupado volta a recusar
		assertThat(admin.patch("/api/admin/planos/" + a, "{\"ativo\":false}").statusCode()).isEqualTo(200);
		var b = id(admin.post("/api/admin/planos", plano("Bom", 5000)));
		assertThat(admin.patch("/api/admin/planos/" + a, "{\"ativo\":true}").statusCode()).isEqualTo(400);
		assertThat(admin.patch("/api/admin/planos/" + b, "{\"nome\":\"Outro\"}").statusCode()).isEqualTo(200);
		assertThat(admin.patch("/api/admin/planos/" + a, "{\"ativo\":true}").statusCode()).isEqualTo(200);
		assertThat(auditorias("plano", a, "ADICAO")).isEqualTo(1);
	}

	@Test
	void planoTecnicoSoAceitaNomeEDescricao() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var gratuito = tecnico("GRATUITO");
		for (var corpo : List.of("{\"valorCentavos\":500}", "{\"ativo\":false}", "{\"ordem\":1}", "{\"complexidade\":\"COM_VERSICULO\"}")) {
			var res = admin.patch("/api/admin/planos/" + gratuito, corpo);
			assertThat(res.statusCode()).as(corpo).isEqualTo(400);
			assertThat(json(res).get("codigo").asString()).isEqualTo("plano_tecnico");
			assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Este plano é usado pelo sistema e só aceita mudanças de nome e descrição.");
		}
		assertThat(auditorias("plano", gratuito, "MUDANCA")).isZero();
		// mudanca recusada nao leva junto o nome do mesmo PATCH
		assertThat(admin.patch("/api/admin/planos/" + gratuito, "{\"nome\":\"Cortesia\",\"ativo\":false}").statusCode()).isEqualTo(400);
		assertThat(jdbc.sql("select nome from plano where id = :id").param("id", gratuito).query(String.class).single()).isEqualTo("Gratuito");
		// formulario inteiro do front: valor 0 igual ao atual e no-op, so a descricao muda
		var ok = admin.patch("/api/admin/planos/" + gratuito, "{\"nome\":\"Gratuito\",\"descricao\":\"outra\",\"valorCentavos\":0,\"complexidade\":\"SIMPLES\",\"ordem\":1000,\"ativo\":true}");
		assertThat(ok.statusCode()).isEqualTo(200);
		var d = Navegador.JSON.readTree(jdbc.sql("select diff::text from auditoria where entidade = 'plano' and entidade_id = :id").param("id", Long.toString(gratuito)).query(String.class).single());
		assertThat(d.propertyNames()).containsExactly("descricao");
		assertThat(admin.patch("/api/admin/planos/" + gratuito, "{\"valorCentavos\":5}").statusCode()).isEqualTo(400);
		ok = admin.patch("/api/admin/planos/" + gratuito, "{\"nome\":\"Cortesia\",\"descricao\":\"nova\",\"ativo\":true}");
		assertThat(ok.statusCode()).isEqualTo(200);
		assertThat(json(ok).get("nome").asString()).isEqualTo("Cortesia");
		assertThat(json(ok).get("tipo").asString()).isEqualTo("GRATUITO");
		assertThat(auditorias("plano", gratuito, "MUDANCA")).isEqualTo(2);
		var livre = tecnico("VALOR_LIVRE");
		assertThat(admin.patch("/api/admin/planos/" + livre, "{\"valorCentavos\":1}").statusCode()).isEqualTo(200); // igual ao atual
		assertThat(admin.patch("/api/admin/planos/" + livre, "{\"valorCentavos\":2}").statusCode()).isEqualTo(400);
	}

	@Test
	void planoComumComValorZeroNoPatchEh400NoCampoValor() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = id(admin.post("/api/admin/planos", plano("Bom", 3000)));
		var res = admin.patch("/api/admin/planos/" + id, "{\"valorCentavos\":0}");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("valorCentavos");
	}

	@Test
	void complexidadeInvalidaEhErroNoCampoComOTextoAntigo() throws Exception {
		var res = entrar(Papel.ADMIN).post("/api/admin/planos", "{\"nome\":\"X\",\"valorCentavos\":100,\"complexidade\":\"xyz\"}");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("complexidade");
		assertThat(json(res).get("erros").get(0).get("mensagem").asString()).isEqualTo("\"xyz\" não é uma escolha válida.");
	}

	@Test
	void renomearTecnicoParaNomeDeAtivoEh400() throws Exception {
		var admin = entrar(Papel.ADMIN);
		admin.post("/api/admin/planos", plano("Bom", 3000));
		var res = admin.patch("/api/admin/planos/" + tecnico("GRATUITO"), "{\"nome\":\"Bom\"}");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("nome");
	}

	@Test
	void duasCriacoesSimultaneasDoMesmoNomeDaoUm201EUm400() throws Exception {
		var a = entrar(Papel.ADMIN);
		var b = entrar(Papel.ADMIN);
		for (var caminho : List.of("/api/admin/planos", "/api/admin/instituicoes")) {
			var corpo = caminho.endsWith("planos") ? plano("Corrida", 1000) : "{\"nome\":\"Corrida\"}";
			var largada = new CountDownLatch(1);
			try (var pool = Executors.newFixedThreadPool(2)) {
				var f1 = pool.submit(() -> { largada.await(); return a.post(caminho, corpo).statusCode(); });
				var f2 = pool.submit(() -> { largada.await(); return b.post(caminho, corpo).statusCode(); });
				largada.countDown();
				assertThat(List.of(f1.get(), f2.get())).as(caminho).containsExactlyInAnyOrder(201, 400);
			}
			limpar();
		}
	}

	// --- instituicoes ---

	@Test
	void criaInstituicaoAtivaEListaComLogo() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var res = admin.post("/api/admin/instituicoes", "{\"nome\":\" Casa \"}");
		assertThat(res.statusCode()).isEqualTo(201);
		var i = json(res);
		assertThat(i.get("nome").asString()).isEqualTo("Casa");
		assertThat(i.get("ativo").asBoolean()).isTrue();
		assertThat(i.get("ordem").asInt()).isZero();
		assertThat(i.get("temLogo").asBoolean()).isFalse();
		assertThat(i.get("logoUrl").isNull()).isTrue();
		var outra = id(admin.post("/api/admin/instituicoes", "{\"nome\":\"Abrigo\",\"ordem\":5}"));
		var sha = "a1b2c3d4".repeat(8);
		logos.saveAndFlush(new InstituicaoLogo(outra, new byte[] {1}, "image/png", sha, Instant.now()));
		var lista = json(admin.get("/api/admin/instituicoes"));
		assertThat(lista.get(0).get("nome").asString()).isEqualTo("Casa");
		assertThat(lista.get(1).get("logoUrl").asString()).isEqualTo("/api/instituicoes/" + outra + "/logo?v=a1b2c3d4");
		assertThat(lista.get(1).get("temLogo").asBoolean()).isTrue();
		assertThat(auditorias("instituicao", i.get("id").asLong(), "ADICAO")).isEqualTo(1);
		assertThat(admin.get("/api/admin/instituicoes").headers().firstValue("Cache-Control").orElse("")).contains("no-store");
	}

	@Test
	void instituicaoExigeNomeELimitaTamanho() throws Exception {
		var admin = entrar(Papel.ADMIN);
		for (var corpo : List.of("{}", "{\"nome\":\"\"}", "{\"nome\":\"   \"}", "{\"nome\":\"" + "a".repeat(121) + "\"}",
				"{\"nome\":\"X\",\"ordem\":-1}")) {
			assertThat(admin.post("/api/admin/instituicoes", corpo).statusCode()).as(corpo).isEqualTo(400);
		}
	}

	@Test
	void desativaReativaEditaEAudita() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = id(admin.post("/api/admin/instituicoes", "{\"nome\":\"Casa\"}"));
		var res = admin.patch("/api/admin/instituicoes/" + id, "{\"ativo\":false,\"ordem\":2}");
		assertThat(json(res).get("ativo").asBoolean()).isFalse();
		assertThat(json(res).get("ordem").asInt()).isEqualTo(2);
		assertThat(json(admin.patch("/api/admin/instituicoes/" + id, "{\"nome\":\"Casa Nova\",\"ativo\":true}")).get("nome").asString()).isEqualTo("Casa Nova");
		assertThat(auditorias("instituicao", id, "MUDANCA")).isEqualTo(2);
		assertThat(admin.patch("/api/admin/instituicoes/" + id, "{\"nome\":\"Casa Nova\"}").statusCode()).isEqualTo(200);
		assertThat(auditorias("instituicao", id, "MUDANCA")).isEqualTo(2); // sem mudanca, sem linha
		assertThat(admin.patch("/api/admin/instituicoes/999999", "{}").statusCode()).isEqualTo(404);
	}

	@Test
	void instituicaoComNomeDeOutraAtivaEh400() throws Exception {
		var admin = entrar(Papel.ADMIN);
		admin.post("/api/admin/instituicoes", "{\"nome\":\"Casa\"}");
		var res = admin.post("/api/admin/instituicoes", "{\"nome\":\"Casa\"}");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("nome");
		var outra = id(admin.post("/api/admin/instituicoes", "{\"nome\":\"Abrigo\"}"));
		assertThat(admin.patch("/api/admin/instituicoes/" + outra, "{\"nome\":\"Casa\"}").statusCode()).isEqualTo(400);
	}
}
