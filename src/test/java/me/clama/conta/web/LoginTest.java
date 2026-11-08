package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import me.clama.BancoDeTeste;
import java.util.concurrent.Semaphore;
import me.clama.conta.VagasDeHash;
import me.clama.conta.Papel;
import me.clama.conta.SessoesDoUsuario;
import me.clama.conta.Usuario;
import me.clama.conta.UsuarioRepository;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class LoginTest {

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired LimiteDeTaxa limite;
	@Autowired SessoesDoUsuario sessoes;
	@Autowired VagasDeHash vagasDeHash;

	@BeforeEach
	void zerarLimites() {
		limite.limpar();
	}

	private Usuario usuario(String email, String senha, Papel papel) {
		return Navegador.usuario(usuarios, encoder, email, senha, papel);
	}

	@Test
	void loginOkDevolveUsuarioECookieDeSessao() throws Exception {
		var email = emailUnico();
		var u = usuario(email, "senha-forte-123", Papel.ADMIN);
		// hash com parametros antigos (como os migrados do Django): o login regrava
		u.definirSenha("{argon2}" + new Argon2PasswordEncoder(16, 32, 1, 1 << 12, 1).encode("senha-forte-123"));
		usuarios.save(u);

		var nav = new Navegador(porta);
		var res = nav.login("  " + email.toUpperCase() + " ", "senha-forte-123");

		assertThat(res.statusCode()).isEqualTo(200);
		var corpo = json(res);
		assertThat(corpo.get("id").asLong()).isEqualTo(u.getId());
		assertThat(corpo.get("email").asString()).isEqualTo(email);
		assertThat(corpo.get("nomeCompleto").asString()).isEqualTo("Fulana de Tal");
		assertThat(corpo.get("nomeFormatoBlog").asString()).isEqualTo("compacto");
		assertThat(corpo.get("papel").asString()).isEqualTo("ADMIN");
		assertThat(corpo.has("senha")).isFalse();

		var sessao = res.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("SESSION=")).toList();
		assertThat(sessao).singleElement().satisfies(c -> assertThat(c)
				.contains("HttpOnly").contains("SameSite=Lax").contains("Max-Age=2592000").doesNotContain("Secure"));

		var salvo = usuarios.findById(u.getId()).orElseThrow();
		assertThat(salvo.getUltimoLoginEm()).isNotNull();
		assertThat(salvo.getSenha()).startsWith("{argon2}").contains("$m=16384,t=2,p=1$");

		var eu = nav.get("/api/auth/eu");
		assertThat(eu.statusCode()).isEqualTo(200);
		assertThat(json(eu).get("email").asString()).isEqualTo(email);
	}

	@Test
	void senhaErradaInativoSemSenhaDao401Generico() throws Exception {
		var errada = emailUnico();
		usuario(errada, "senha-forte-123", Papel.CLIENTE);
		var inativo = emailUnico();
		var u = usuario(inativo, "senha-forte-123", Papel.CLIENTE);
		u.desativar();
		usuarios.save(u);
		var semSenha = emailUnico();
		usuario(semSenha, null, Papel.CLIENTE);
		var hashEmBranco = emailUnico();
		var b = usuario(hashEmBranco, null, Papel.CLIENTE);
		b.definirSenha("   ");
		usuarios.save(b);

		for (var tentativa : new String[][] { { errada, "outra-senha-456" }, { inativo, "senha-forte-123" },
				{ semSenha, "senha-forte-123" }, { hashEmBranco, "senha-forte-123" },
				{ emailUnico(), "senha-forte-123" } }) {
			var nav = new Navegador(porta);
			var res = nav.login(tentativa[0], tentativa[1]);
			assertThat(res.statusCode()).as(tentativa[0]).isEqualTo(401);
			assertThat(json(res).get("codigo").asString()).isEqualTo("credenciais_invalidas");
			assertThat(json(res).get("detail").asString()).isEqualTo("Email ou senha inválidos.");
			assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Email ou senha inválidos.");
			assertThat(nav.cookie("SESSION")).isNull();
		}
	}

	@Test
	void idDaSessaoMudaNoLogin() throws Exception {
		var email = emailUnico();
		usuario(email, "senha-forte-123", Papel.CLIENTE);
		var nav = new Navegador(porta);
		nav.login(email, "senha-forte-123");
		var antiga = nav.cookie("SESSION");

		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		var nova = nav.cookie("SESSION");

		assertThat(nova).isNotNull().isNotEqualTo(antiga);
		assertThat(nav.get("/api/auth/eu").statusCode()).isEqualTo(200);
		var comAntiga = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/auth/eu"))
						.header("Cookie", "SESSION=" + antiga).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(comAntiga.statusCode()).isEqualTo(401);
	}

	@Test
	void postAutenticadoLogoDepoisDoLoginPassa() throws Exception {
		var email = emailUnico();
		usuario(email, "senha-forte-123", Papel.ADMIN);
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		var tokenAnonimo = nav.cookie("XSRF-TOKEN");

		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);

		assertThat(nav.cookie("XSRF-TOKEN")).isNotNull().isNotEqualTo(tokenAnonimo);
		assertThat(nav.post("/api/admin/teste", "{}").statusCode()).isEqualTo(200);
	}

	@Test
	void anonimo401NaoCriaSessao() throws Exception {
		var antes = jdbc.queryForObject("select count(*) from spring_session", Long.class);
		var nav = new Navegador(porta);
		for (var caminho : new String[] { "/api/auth/eu", "/api/admin/x", "/api/conta/x" }) {
			var res = nav.get(caminho);
			assertThat(res.statusCode()).as(caminho).isEqualTo(401);
			assertThat(res.headers().allValues("Set-Cookie")).noneMatch(c -> c.contains("SESSION"));
		}
		assertThat(nav.cookie("SESSION")).isNull();
		assertThat(jdbc.queryForObject("select count(*) from spring_session", Long.class)).isEqualTo(antes);
	}

	@Test
	void logoutDuasVezes204() throws Exception {
		var email = emailUnico();
		var u = usuario(email, "senha-forte-123", Papel.CLIENTE);
		var nav = new Navegador(porta);
		nav.login(email, "senha-forte-123");

		assertThat(nav.post("/api/auth/logout", "").statusCode()).isEqualTo(204);
		assertThat(nav.cookie("XSRF-TOKEN")).isNull(); // o token da sessao morta nao sobrevive
		assertThat(nav.get("/api/auth/eu").statusCode()).isEqualTo(401); // o front refaz o GET e ganha outro
		assertThat(linhasDeSessao(u)).isZero();
		assertThat(nav.post("/api/auth/logout", "").statusCode()).isEqualTo(204);
	}

	@Test
	void euSemSessao401() throws Exception {
		var res = new Navegador(porta).get("/api/auth/eu");
		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(json(res).get("codigo").asString()).isEqualTo("nao_autenticado");
		assertThat(res.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
	}

	@Test
	void sessaoSobreviveAoRestartDoContexto() throws Exception {
		var email = emailUnico();
		var u = usuario(email, "senha-forte-123", Papel.CLIENTE);
		new Navegador(porta).login(email, "senha-forte-123");

		// a sessao mora no Postgres, indexada pelo id: outro processo a le
		var expira = jdbc.queryForObject("select max_inactive_interval from spring_session where principal_name = ?",
				Integer.class, u.getId().toString());
		assertThat(expira).isEqualTo(7 * 24 * 3600);
	}

	@Test
	void derrubaOutrasSessoesETodas() throws Exception {
		var email = emailUnico();
		var u = usuario(email, "senha-forte-123", Papel.CLIENTE);
		var a = new Navegador(porta);
		var b = new Navegador(porta);
		a.login(email, "senha-forte-123");
		b.login(email, "senha-forte-123");
		var idDeA = new String(Base64.getDecoder().decode(a.cookie("SESSION")), StandardCharsets.UTF_8);

		sessoes.derrubarOutras(u.getId(), idDeA);
		assertThat(a.get("/api/auth/eu").statusCode()).isEqualTo(200);
		assertThat(b.get("/api/auth/eu").statusCode()).isEqualTo(401);

		sessoes.derrubarTodas(u.getId());
		assertThat(a.get("/api/auth/eu").statusCode()).isEqualTo(401);
	}

	@Test
	void emailEnormeDa400() throws Exception {
		var res = new Navegador(porta).login("a".repeat(288) + "@exemplo.com", "x".repeat(1025));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		assertThat(json(res).get("erros").findValuesAsString("campo")).containsExactlyInAnyOrder("email", "senha");
	}

	@Test
	void loginSemCsrfDa403() throws Exception {
		var nav = new Navegador(porta); // sem o GET antes: nenhum cookie, nenhum header
		var res = nav.post("/api/auth/login", "{\"email\":\"a@exemplo.com\",\"senha\":\"x\"}");
		assertThat(res.statusCode()).isEqualTo(403);
		assertThat(json(res).get("codigo").asString()).isEqualTo("csrf_invalido");
	}

	@Test
	void semVagaParaCalcularHashDa503() throws Exception {
		var vagas = (Semaphore) ReflectionTestUtils.getField(vagasDeHash, "vagas");
		var tomadas = vagas.drainPermits();
		try {
			var res = new Navegador(porta).login(emailUnico(), "qualquer-senha"); // espera os 5 s e desiste
			assertThat(res.statusCode()).isEqualTo(503);
			assertThat(json(res).get("codigo").asString()).isEqualTo("ocupado");
			assertThat(json(res).get("mensagemPastoral").asString())
					.isEqualTo("Muita gente entrando agora — tente de novo em instantes.");
		} finally {
			vagas.release(tomadas);
		}
	}

	private long linhasDeSessao(Usuario u) {
		return jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
				u.getId().toString());
	}
}
