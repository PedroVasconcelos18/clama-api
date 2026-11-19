package me.clama.conta.web;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Review Focus 3: o cadastro nao revela se a conta existe; descartavel e recusado antes. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({BancoDeTeste.class, EmailDeTeste.class})
class CadastroTest {

	static final String PASTORAL = "Se este e-mail puder ser usado, você vai receber em instantes um link para criar sua senha. Confira também o spam.";
	static final WireMockServer TURNSTILE = new WireMockServer(WireMockConfiguration.options().dynamicPort());

	static {
		TURNSTILE.start();
	}

	@DynamicPropertySource
	static void turnstile(DynamicPropertyRegistry r) {
		r.add("clama.turnstile.url", () -> TURNSTILE.baseUrl() + "/siteverify");
		r.add("clama.turnstile.secret", () -> "segredo-de-teste");
	}

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		ociosa();
		limite.limpar();
		caixa.limpar();
		TURNSTILE.resetAll();
		TURNSTILE.stubFor(post("/siteverify").willReturn(okJson("{\"success\":true}")));
		jdbc.update("delete from usuario where email like 'cad-%'");
	}

	private void ociosa() throws Exception {
		var prazo = System.nanoTime() + 10_000_000_000L;
		while (envio.pendentes() > 0 && System.nanoTime() < prazo) Thread.sleep(10);
		assertThat(envio.pendentes()).isZero();
	}

	static String email() {
		return "cad-" + UUID.randomUUID() + "@exemplo.com";
	}

	private HttpResponse<String> cadastrar(String email, String turnstileToken) throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu"); // CSRF
		var corpo = new LinkedHashMap<String, Object>();
		corpo.put("nome", "Maria Souza");
		corpo.put("email", email);
		if (turnstileToken != null) corpo.put("turnstileToken", turnstileToken);
		return nav.post("/api/auth/cadastro", Navegador.JSON.writeValueAsString(corpo));
	}

	private HttpResponse<String> cadastrar(String email) throws Exception {
		return cadastrar(email, "token-ok");
	}

	private int contas(String email) {
		return jdbc.queryForObject("select count(*) from usuario where email = ?", Integer.class, email);
	}

	@Test
	void cadastroCriaContaSemSenhaEMandaLink() throws Exception {
		var email = email();
		var res = cadastrar(email);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo(PASTORAL);
		caixa.aguardarPara(email, 1);
		ociosa();
		var u = usuarios.findByEmail(email).orElseThrow();
		assertThat(u.temSenha()).isFalse();
		assertThat(u.getPapel()).isEqualTo(Papel.CLIENTE);
		assertThat(u.getNomeCompleto()).isEqualTo("Maria Souza");
		assertThat(caixa.enviados()).singleElement().satisfies(e -> {
			assertThat(e.email().assunto()).isEqualTo("Bem-vinda ao Clama — defina sua senha"); // modelo definir_senha
			assertThat(e.email().texto()).contains("http://localhost:5173/definir-senha#token=").contains("Maria");
		});
	}

	@Test
	void cadastroRespondeIgualParaContaExistente() throws Exception {
		var existente = email();
		Navegador.usuario(usuarios, encoder, existente, "senha-forte-123", Papel.CLIENTE);
		var nova = cadastrar(email());
		ociosa();
		caixa.limpar();

		var res = cadastrar(existente);
		assertThat(res.statusCode()).isEqualTo(nova.statusCode()).isEqualTo(200);
		assertThat(res.body()).isEqualTo(nova.body());
		caixa.aguardarPara(existente, 1);
		ociosa();
		assertThat(contas(existente)).isOne();
		assertThat(usuarios.findByEmail(existente).orElseThrow().getNomeCompleto()).isEqualTo("Fulana de Tal"); // nada mudou
		assertThat(caixa.enviados()).singleElement().satisfies(e -> {
			assertThat(e.email().para()).isEqualTo(existente);
			assertThat(e.email().assunto()).isEqualTo("Redefina sua senha — Clama"); // modelo redefinicao_senha
			assertThat(e.email().texto()).contains("http://localhost:5173/redefinir-senha#token=");
		});
	}

	@Test
	void cadastroRecusaEmailDescartavel() throws Exception {
		var email = "cad-" + UUID.randomUUID() + "@mailinator.com";
		var res = cadastrar(email);
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("email_descartavel");
		ociosa();
		assertThat(contas(email)).isZero();
		assertThat(caixa.enviados()).isEmpty();
	}

	@Test
	void cadastroSemTurnstileE400() throws Exception {
		var email = email();
		var sem = cadastrar(email, null);
		assertThat(sem.statusCode()).isEqualTo(400);
		assertThat(json(sem).get("erros").get(0).get("campo").asString()).isEqualTo("turnstileToken");

		TURNSTILE.stubFor(post("/siteverify").willReturn(okJson("{\"success\":false}")));
		var recusado = cadastrar(email);
		assertThat(recusado.statusCode()).isEqualTo(400);
		assertThat(json(recusado).get("codigo").asString()).isEqualTo("captcha_invalido");
		ociosa();
		assertThat(contas(email)).isZero();
		assertThat(caixa.enviados()).isEmpty();
	}

	@Test
	void limitesDoCadastro() throws Exception {
		var email = email();
		for (int i = 0; i < 3; i++) assertThat(cadastrar(email).statusCode()).isEqualTo(200);
		var res = cadastrar(email);
		assertThat(res.statusCode()).isEqualTo(429);
		assertThat(json(res).get("codigo").asString()).isEqualTo("limite_excedido");
	}
}
