package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.Papel;
import me.clama.conta.UsuarioRepository;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({BancoDeTeste.class, EmailDeTeste.class})
class EsqueciSenhaTest {

	static final String PASTORAL = "Se este e-mail estiver cadastrado, você receberá um link para criar uma nova senha em instantes. Verifique também a caixa de spam.";

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;

	@BeforeEach
	void zerar() throws Exception {
		ociosa();
		limite.limpar();
		caixa.limpar();
	}

	/** Espera o executor de e-mail esvaziar (prazo de 10 s). */
	private void ociosa() throws Exception {
		var prazo = System.nanoTime() + 10_000_000_000L;
		while (envio.pendentes() > 0 && System.nanoTime() < prazo) Thread.sleep(10);
		assertThat(envio.pendentes()).isZero();
	}

	private java.net.http.HttpResponse<String> pedir(String email) throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu"); // o CSRF vale tambem para o anonimo
		return nav.post("/api/auth/esqueci-senha", Navegador.JSON.writeValueAsString(Map.of("email", email)));
	}

	@Test
	void mesmaRespostaParaExistenteInexistenteInativo() throws Exception {
		var existente = emailUnico();
		Navegador.usuario(usuarios, encoder, existente, "senha-forte-123", Papel.CLIENTE);
		var inativo = emailUnico();
		var u = Navegador.usuario(usuarios, encoder, inativo, "senha-forte-123", Papel.CLIENTE);
		u.desativar();
		usuarios.save(u);

		var corpos = new ArrayList<String>();
		for (var email : new String[] {existente, emailUnico(), inativo}) {
			var res = pedir(email);
			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo(PASTORAL);
			corpos.add(res.body());
		}
		assertThat(corpos).hasSize(3).allMatch(c -> c.equals(corpos.get(0)));
		caixa.aguardarPara(existente, 1);
		ociosa();
		assertThat(caixa.enviados()).hasSize(1);
		var enviado = caixa.enviados().get(0);
		assertThat(enviado.email().para()).isEqualTo(existente);
		assertThat(enviado.email().texto()).contains("http://localhost:5173/redefinir-senha?token=");
		assertThat(enviado.email().texto()).contains("Fulana"); // primeiro nome
		assertThat(enviado.chave()).isNotBlank();
	}

	@Test
	void tempoParecido() throws Exception {
		var existente = emailUnico();
		Navegador.usuario(usuarios, encoder, existente, "senha-forte-123", Papel.CLIENTE);
		pedir(existente); // aquece JIT, pool e conexoes
		pedir(emailUnico());
		ociosa();
		caixa.atrasar(500); // a Resend lenta nao pode aparecer no tempo da resposta
		var comConta = new ArrayList<Long>();
		var semConta = new ArrayList<Long>();
		for (int i = 0; i < 5; i++) {
			limite.limpar();
			comConta.add(medir(existente));
			limite.limpar();
			semConta.add(medir(emailUnico()));
		}
		Collections.sort(comConta);
		Collections.sort(semConta);
		var comContaMs = Duration.ofNanos(comConta.get(2));
		var semContaMs = Duration.ofNanos(semConta.get(2));
		assertThat(comContaMs).isLessThan(Duration.ofMillis(200));
		assertThat(semContaMs).isLessThan(Duration.ofMillis(200));
		assertThat(Duration.ofNanos(Math.abs(comConta.get(2) - semConta.get(2)))).isLessThan(Duration.ofMillis(200));
		caixa.atrasar(0);
	}

	private long medir(String email) throws Exception {
		var t0 = System.nanoTime();
		assertThat(pedir(email).statusCode()).isEqualTo(200);
		return System.nanoTime() - t0;
	}

	@Test
	void adminRecebeLink() throws Exception {
		var email = emailUnico();
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.ADMIN);
		assertThat(pedir(email).statusCode()).isEqualTo(200);
		assertThat(caixa.aguardarPara(email, 1)).hasSize(1);
	}

	@Test
	void gmailCanonicoAcha() throws Exception {
		var local = "pessoa" + System.nanoTime();
		Navegador.usuario(usuarios, encoder, local + "@gmail.com", "senha-forte-123", Papel.CLIENTE);
		assertThat(pedir(local.substring(0, 3) + "." + local.substring(3) + "+x@Gmail.com").statusCode()).isEqualTo(200);
		assertThat(caixa.aguardarPara(local + "@gmail.com", 1)).hasSize(1);
	}

	@Test
	void falhaNoEnvioAindaDa200() throws Exception {
		var email = emailUnico();
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		caixa.falhar(true);
		var res = pedir(email);
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo(PASTORAL);
		ociosa(); // a falha foi engolida no executor (log + Sentry)
	}

	@Test
	void variantesDoGmailCompartilhamOLimite() throws Exception {
		for (var v : new String[] {"a.b+1@gmail.com", "ab@gmail.com", "a.b@Gmail.com"}) {
			assertThat(pedir(v).statusCode()).isEqualTo(200);
		}
		assertThat(pedir("a.b+2@gmail.com").statusCode()).isEqualTo(429);
	}

	@Test
	void limitePorEmail() throws Exception {
		var email = emailUnico();
		for (int i = 0; i < 3; i++) assertThat(pedir(email).statusCode()).isEqualTo(200);
		var res = pedir(email);
		assertThat(res.statusCode()).isEqualTo(429);
		assertThat(json(res).get("codigo").asString()).isEqualTo("limite_excedido");
	}

	@Test
	void emailVazioOuMalFormadoDa400() throws Exception {
		assertThat(pedir("").statusCode()).isEqualTo(400);
		assertThat(pedir("sem-arroba").statusCode()).isEqualTo(400);
		assertThat(pedir("a".repeat(250) + "@b.com").statusCode()).isEqualTo(400);
	}
}
