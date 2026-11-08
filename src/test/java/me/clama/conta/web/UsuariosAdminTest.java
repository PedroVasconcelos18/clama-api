package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.Usuario;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.infra.seguranca.LimiteDeTaxa;
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
class UsuariosAdminTest {

	static final String SENHA = "senha-forte-123";
	static final String NOVA = "outra-senha-forte-9";

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired JdbcClient jdbc;

	@BeforeEach
	void isolar() {
		limite.limpar();
		jdbc.sql("delete from usuario where papel = 'SUPERUSUARIO'").update(); // a guarda conta o banco todo
	}

	private Usuario conta(Papel papel) {
		return Navegador.usuario(usuarios, encoder, emailUnico(), SENHA, papel);
	}

	private Navegador entrar(Usuario u) throws Exception {
		var nav = new Navegador(porta);
		assertThat(nav.login(u.getEmail(), SENHA).statusCode()).isEqualTo(200);
		return nav;
	}

	private long auditorias(Long id, String acao) {
		return jdbc.sql("select count(*) from auditoria where entidade = 'usuario' and entidade_id = :id and acao = :a")
				.param("id", id.toString()).param("a", acao).query(Long.class).single();
	}

	private static String papel(Papel p) {
		return "{\"papel\":\"" + p + "\"}";
	}

	// --- permissao ---

	@Test
	void adminComumLeMasNaoEscreve() throws Exception {
		var admin = entrar(conta(Papel.ADMIN));
		var alvo = conta(Papel.CLIENTE);
		assertThat(admin.get("/api/admin/usuarios").statusCode()).isEqualTo(200);
		assertThat(admin.get("/api/admin/usuarios/" + alvo.getId()).statusCode()).isEqualTo(200);
		for (var res : java.util.List.of(
				admin.post("/api/admin/usuarios", "{\"email\":\"" + emailUnico() + "\",\"nomeCompleto\":\"X\",\"senha\":\"" + SENHA + "\"}"),
				admin.patch("/api/admin/usuarios/" + alvo.getId(), "{\"ativo\":false}"),
				admin.post("/api/admin/usuarios/" + alvo.getId() + "/senha", "{\"senha\":\"" + NOVA + "\"}"),
				admin.delete("/api/admin/usuarios/" + alvo.getId()))) {
			assertThat(res.statusCode()).isEqualTo(403);
			assertThat(json(res).get("codigo").asString()).isEqualTo("sem_permissao");
			assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Só o superusuário gerencia usuários.");
		}
		assertThat(usuarios.findById(alvo.getId()).orElseThrow().isAtivo()).isTrue();
	}

	@Test
	void clienteEAnonimoNaoEntram() throws Exception {
		assertThat(entrar(conta(Papel.CLIENTE)).get("/api/admin/usuarios").statusCode()).isEqualTo(403);
		assertThat(new Navegador(porta).get("/api/admin/usuarios").statusCode()).isEqualTo(401);
	}

	// --- leitura ---

	@Test
	void listaPaginadaNoFormatoPagedModel() throws Exception {
		var su = conta(Papel.SUPERUSUARIO);
		for (int i = 0; i < 3; i++) conta(Papel.CLIENTE);
		var res = entrar(su).get("/api/admin/usuarios?size=2");
		assertThat(res.statusCode()).isEqualTo(200);
		var corpo = json(res);
		assertThat(corpo.get("content")).hasSize(2);
		assertThat(corpo.get("page").get("size").asInt()).isEqualTo(2);
		assertThat(corpo.get("page").get("totalElements").asLong()).isGreaterThanOrEqualTo(4);
		var item = corpo.get("content").get(0);
		assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "email", "nomeCompleto", "papel", "ativo",
				"criadoEm", "ultimoLoginEm", "temSenha");
	}

	@Test
	void tamanhoPadraoVinteEMaximoCem() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		assertThat(json(su.get("/api/admin/usuarios")).get("page").get("size").asInt()).isEqualTo(20);
		assertThat(json(su.get("/api/admin/usuarios?size=1000")).get("page").get("size").asInt()).isEqualTo(100);
	}

	@Test
	void ordemFixaPorCriacaoDecrescenteEOrdenacaoDoClienteEhIgnorada() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		assertThat(su.get("/api/admin/usuarios?sort=senha,asc").statusCode()).isEqualTo(200);
	}

	@Test
	void filtros() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var marca = UUID.randomUUID().toString().substring(0, 8);
		var a = usuarios.save(Usuario.novo(marca + "-a@exemplo.com", "Ana Silva", Papel.CLIENTE, Instant.now()));
		var b = usuarios.save(Usuario.novo(marca + "-b@exemplo.com", "Beto " + marca.toUpperCase(), Papel.ADMIN, Instant.now()));
		var c = usuarios.save(Usuario.novo(marca + "-c@exemplo.com", "Caio", Papel.CLIENTE, Instant.now()));
		c.desativar();
		usuarios.save(c);
		assertThat(ids(su, "q=" + marca)).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
		assertThat(ids(su, "q=" + marca + "&papel=ADMIN")).containsExactly(b.getId());
		assertThat(ids(su, "q=" + marca + "&ativo=false")).containsExactly(c.getId());
		assertThat(ids(su, "q=" + marca + "&ativo=true&papel=CLIENTE")).containsExactly(a.getId());
		assertThat(ids(su, "q=ANA SILVA")).contains(a.getId());
		assertThat(su.get("/api/admin/usuarios?papel=INVALIDO").statusCode()).isEqualTo(400);
	}

	@Test
	void buscaNaoTrataPorcentoNemSublinhadoComoCuringa() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var marca = UUID.randomUUID().toString().substring(0, 8);
		var com = usuarios.save(Usuario.novo(marca + "_x@exemplo.com", "Um", Papel.CLIENTE, Instant.now()));
		usuarios.save(Usuario.novo(marca + "yx@exemplo.com", "Dois", Papel.CLIENTE, Instant.now()));
		assertThat(ids(su, "q=" + marca + "_x")).containsExactly(com.getId());
		assertThat(ids(su, "q=%25")).isEmpty();
	}

	@Test
	void buscaGrandeDemaisEh400() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		assertThat(su.get("/api/admin/usuarios?q=" + "a".repeat(101)).statusCode()).isEqualTo(400);
	}

	private java.util.List<Long> ids(Navegador nav, String query) throws Exception {
		var res = nav.get("/api/admin/usuarios?size=100&" + query.replace(" ", "%20"));
		assertThat(res.statusCode()).isEqualTo(200);
		return json(res).get("content").valueStream().map(n -> n.get("id").asLong()).toList();
	}

	@Test
	void detalheEInexistente() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.CLIENTE);
		var corpo = json(su.get("/api/admin/usuarios/" + alvo.getId()));
		assertThat(corpo.get("email").asString()).isEqualTo(alvo.getEmail());
		assertThat(corpo.get("temSenha").asBoolean()).isTrue();
		assertThat(corpo.get("papel").asString()).isEqualTo("CLIENTE");
		assertThat(corpo.has("senha")).isFalse();
		var res = su.get("/api/admin/usuarios/999999999");
		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(json(res).get("codigo").asString()).isEqualTo("nao_encontrado");
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Esse usuário não foi encontrado.");
	}

	// --- criar admin ---

	private static String novo(String email, String senha) {
		return "{\"email\":\"" + email + "\",\"nomeCompleto\":\"Nova Admin\",\"senha\":\"" + senha + "\"}";
	}

	@Test
	void criaAdminQueJaConsegueEntrar() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var email = emailUnico().toUpperCase();
		var res = su.post("/api/admin/usuarios", novo(email, NOVA));
		assertThat(res.statusCode()).isEqualTo(201);
		var corpo = json(res);
		assertThat(corpo.get("papel").asString()).isEqualTo("ADMIN");
		assertThat(corpo.get("email").asString()).isEqualTo(email.toLowerCase());
		assertThat(corpo.get("temSenha").asBoolean()).isTrue();
		assertThat(auditorias(corpo.get("id").asLong(), "ADICAO")).isEqualTo(1);
		var nova = new Navegador(porta);
		assertThat(nova.login(email.toLowerCase(), NOVA).statusCode()).isEqualTo(200);
		assertThat(nova.get("/api/admin/usuarios").statusCode()).isEqualTo(200);
	}

	@Test
	void emailDuplicadoOuCanonicoDuplicado() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var existente = conta(Papel.CLIENTE);
		var gmail = "ma.ria" + System.nanoTime() + "@gmail.com";
		usuarios.save(Usuario.novo(gmail, "M", Papel.CLIENTE, Instant.now()));
		for (var email : java.util.List.of(existente.getEmail(), existente.getEmail().toUpperCase(),
				gmail.replace("ma.ria", "maria"), gmail.replace("@", "+tag@"))) {
			var res = su.post("/api/admin/usuarios", novo(email, NOVA));
			assertThat(res.statusCode()).as(email).isEqualTo(400);
			var erro = json(res).get("erros").get(0);
			assertThat(erro.get("campo").asString()).isEqualTo("email");
			assertThat(erro.get("mensagem").asString()).isEqualTo("Usuário com este E-mail já existe.");
		}
		assertThat(usuarios.findByEmail(existente.getEmail()).orElseThrow().getPapel()).isEqualTo(Papel.CLIENTE);
	}

	@Test
	void senhaFracaOuParecidaComOEmail() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var email = emailUnico();
		var res = su.post("/api/admin/usuarios", novo(email, "12345"));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("senha");
		assertThat(su.post("/api/admin/usuarios", novo(email, email)).statusCode()).isEqualTo(400);
		assertThat(usuarios.findByEmail(email)).isEmpty();
	}

	@Test
	void entradaGrandeDemaisOuInvalidaEh400() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var longo = "a".repeat(250) + "@exemplo.com";
		assertThat(su.post("/api/admin/usuarios", novo(longo, NOVA)).statusCode()).isEqualTo(400);
		assertThat(su.post("/api/admin/usuarios", novo(emailUnico(), "a".repeat(1025))).statusCode()).isEqualTo(400);
		assertThat(su.post("/api/admin/usuarios", "{\"email\":\"" + emailUnico() + "\",\"nomeCompleto\":\"" + "n".repeat(121)
				+ "\",\"senha\":\"" + NOVA + "\"}").statusCode()).isEqualTo(400);
		assertThat(su.post("/api/admin/usuarios", novo("nao-eh-email", NOVA)).statusCode()).isEqualTo(400);
		var alvo = conta(Papel.CLIENTE);
		assertThat(su.post("/api/admin/usuarios/" + alvo.getId() + "/senha", "{\"senha\":\"" + "a".repeat(1025) + "\"}").statusCode())
				.isEqualTo(400);
	}

	// --- patch ---

	@Test
	void naoAlteraASiMesmo() throws Exception {
		var eu = conta(Papel.SUPERUSUARIO);
		conta(Papel.SUPERUSUARIO);
		var res = entrar(eu).patch("/api/admin/usuarios/" + eu.getId(), papel(Papel.ADMIN));
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(json(res).get("codigo").asString()).isEqualTo("nao_pode_alterar_a_si_mesmo");
		assertThat(usuarios.findById(eu.getId()).orElseThrow().getPapel()).isEqualTo(Papel.SUPERUSUARIO);
	}

	@Test
	void ultimoSuperusuarioNaoPerdePapelNemFicaInativoNemEhExcluido() throws Exception {
		var eu = conta(Papel.SUPERUSUARIO);
		var unico = conta(Papel.SUPERUSUARIO);
		var nav = entrar(eu);
		eu.promover(Papel.ADMIN); // a sessao ainda diz SUPERUSUARIO: o outro superusuario e o ultimo de verdade
		usuarios.save(eu);
		for (var res : java.util.List.of(nav.patch("/api/admin/usuarios/" + unico.getId(), papel(Papel.ADMIN)),
				nav.patch("/api/admin/usuarios/" + unico.getId(), "{\"ativo\":false}"),
				nav.delete("/api/admin/usuarios/" + unico.getId()))) {
			assertThat(res.statusCode()).isEqualTo(409);
			assertThat(json(res).get("codigo").asString()).isEqualTo("ultimo_superusuario");
		}
		var depois = usuarios.findById(unico.getId()).orElseThrow();
		assertThat(depois.getPapel()).isEqualTo(Papel.SUPERUSUARIO);
		assertThat(depois.isAtivo()).isTrue();
	}

	@Test
	void promoveESoSuperusuarioFazIsso() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.ADMIN);
		var res = su.patch("/api/admin/usuarios/" + alvo.getId(), papel(Papel.SUPERUSUARIO));
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("papel").asString()).isEqualTo("SUPERUSUARIO");
		assertThat(auditorias(alvo.getId(), "MUDANCA")).isEqualTo(1);
		var diff = jdbc.sql("select diff::text from auditoria where entidade_id = :id").param("id", alvo.getId().toString())
				.query(String.class).single();
		assertThat(diff).contains("ADMIN").contains("SUPERUSUARIO");
	}

	@Test
	void semMudancaNaoAuditaNemDerruba() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.ADMIN);
		var alvoNav = entrar(alvo);
		assertThat(su.patch("/api/admin/usuarios/" + alvo.getId(), "{\"papel\":\"ADMIN\",\"ativo\":true}").statusCode()).isEqualTo(200);
		assertThat(su.patch("/api/admin/usuarios/" + alvo.getId(), "{}").statusCode()).isEqualTo(200);
		assertThat(auditorias(alvo.getId(), "MUDANCA")).isZero();
		assertThat(alvoNav.get("/api/auth/eu").statusCode()).isEqualTo(200);
	}

	@Test
	void rebaixarDerrubaAsSessoesDoAlvo() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.ADMIN);
		var alvoNav = entrar(alvo);
		assertThat(alvoNav.get("/api/admin/usuarios").statusCode()).isEqualTo(200);
		assertThat(su.patch("/api/admin/usuarios/" + alvo.getId(), papel(Papel.CLIENTE)).statusCode()).isEqualTo(200);
		assertThat(alvoNav.get("/api/auth/eu").statusCode()).isEqualTo(401);
	}

	@Test
	void desativarDerrubaESaiDoLoginEReativarNaoDerruba() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.CLIENTE);
		var alvoNav = entrar(alvo);
		assertThat(su.patch("/api/admin/usuarios/" + alvo.getId(), "{\"ativo\":false}").statusCode()).isEqualTo(200);
		assertThat(alvoNav.get("/api/auth/eu").statusCode()).isEqualTo(401);
		assertThat(new Navegador(porta).login(alvo.getEmail(), SENHA).statusCode()).isEqualTo(401);
		assertThat(su.patch("/api/admin/usuarios/" + alvo.getId(), "{\"ativo\":true}").statusCode()).isEqualTo(200);
		assertThat(new Navegador(porta).login(alvo.getEmail(), SENHA).statusCode()).isEqualTo(200);
	}

	@Test
	void desativarRevogaLinksPendentes() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.CLIENTE);
		linkPendente(alvo);
		assertThat(su.patch("/api/admin/usuarios/" + alvo.getId(), "{\"ativo\":false}").statusCode()).isEqualTo(200);
		assertThat(pendentes(alvo)).isZero();
	}

	@Test
	void patchDeInexistente() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		assertThat(su.patch("/api/admin/usuarios/999999999", "{\"ativo\":false}").statusCode()).isEqualTo(404);
	}

	// --- senha ---

	private void linkPendente(Usuario u) {
		jdbc.sql("insert into token_de_senha (usuario_id, hash, finalidade, criado_em, expira_em) values (:u, :h, 'REDEFINIR', now(), now() + interval '1 hour')")
				.param("u", u.getId()).param("h", "%064x".formatted(System.nanoTime())).update();
	}

	private long pendentes(Usuario u) {
		return jdbc.sql("select count(*) from token_de_senha where usuario_id = :u and usado_em is null").param("u", u.getId())
				.query(Long.class).single();
	}

	@Test
	void definirSenhaTrocaRevogaLinksDerrubaSessoesEAudita() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.CLIENTE);
		var alvoNav = entrar(alvo);
		linkPendente(alvo);
		var res = su.post("/api/admin/usuarios/" + alvo.getId() + "/senha", "{\"senha\":\"" + NOVA + "\"}");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(alvoNav.get("/api/auth/eu").statusCode()).isEqualTo(401);
		assertThat(pendentes(alvo)).isZero();
		assertThat(new Navegador(porta).login(alvo.getEmail(), SENHA).statusCode()).isEqualTo(401);
		assertThat(new Navegador(porta).login(alvo.getEmail(), NOVA).statusCode()).isEqualTo(200);
		var diff = jdbc.sql("select diff::text from auditoria where entidade_id = :id and acao = 'MUDANCA'")
				.param("id", alvo.getId().toString()).query(String.class).single();
		assertThat(diff).contains("alterada").doesNotContain(NOVA);
	}

	@Test
	void definirSenhaFracaEhRejeitadaESemMudar() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.CLIENTE);
		var res = su.post("/api/admin/usuarios/" + alvo.getId() + "/senha", "{\"senha\":\"12345\"}");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("senha");
		assertThat(new Navegador(porta).login(alvo.getEmail(), SENHA).statusCode()).isEqualTo(200);
		assertThat(su.post("/api/admin/usuarios/999999999/senha", "{\"senha\":\"" + NOVA + "\"}").statusCode()).isEqualTo(404);
	}

	// --- excluir ---

	@Test
	void naoExcluiASiMesmo() throws Exception {
		var eu = conta(Papel.SUPERUSUARIO);
		var res = entrar(eu).delete("/api/admin/usuarios/" + eu.getId());
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(json(res).get("codigo").asString()).isEqualTo("nao_pode_excluir_a_si_mesmo");
	}

	@Test
	void excluiDerrubaSessoesApagaTokensEMantemATrilha() throws Exception {
		var su = entrar(conta(Papel.SUPERUSUARIO));
		var alvo = conta(Papel.ADMIN);
		var alvoNav = entrar(alvo);
		linkPendente(alvo);
		var res = su.delete("/api/admin/usuarios/" + alvo.getId());
		assertThat(res.statusCode()).isEqualTo(204);
		assertThat(usuarios.findById(alvo.getId())).isEmpty();
		assertThat(alvoNav.get("/api/auth/eu").statusCode()).isEqualTo(401);
		assertThat(jdbc.sql("select count(*) from token_de_senha where usuario_id = :u").param("u", alvo.getId()).query(Long.class).single()).isZero();
		assertThat(auditorias(alvo.getId(), "EXCLUSAO")).isEqualTo(1);
		assertThat(su.delete("/api/admin/usuarios/" + alvo.getId()).statusCode()).isEqualTo(404);
	}
}
