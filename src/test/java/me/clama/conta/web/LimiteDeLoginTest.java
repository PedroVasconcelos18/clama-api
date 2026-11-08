package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.http.HttpResponse;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class LimiteDeLoginTest {

	static final String PASTORAL =
			"Você fez vários pedidos seguidos — espera um instante e tenta de novo, com calma.";

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;

	@BeforeEach
	void zerarLimites() {
		limite.limpar();
	}

	private static void assert429(HttpResponse<String> res) {
		assertThat(res.statusCode()).isEqualTo(429);
		assertThat(json(res).get("codigo").asString()).isEqualTo("limite_excedido");
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo(PASTORAL);
	}

	@Test
	void sextaTentativaNoMesmoEmailDa429() throws Exception {
		var email = emailUnico();
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var nav = new Navegador(porta);
		for (int i = 0; i < 5; i++) assertThat(nav.login(email, "errada-" + i).statusCode()).isEqualTo(401);

		assert429(nav.login(email, "senha-forte-123")); // nem a senha certa passa
		assert429(nav.login(email.toUpperCase(), "senha-forte-123")); // a chave e o e-mail minusculo
		// a cota de um e-mail nao afeta outro
		var outro = emailUnico();
		Navegador.usuario(usuarios, encoder, outro, "senha-forte-123", Papel.CLIENTE);
		assertThat(nav.login(outro, "senha-forte-123").statusCode()).isEqualTo(200);
	}

	@Test
	void xffForjadoNaoBurlaLimitePorEmail() throws Exception {
		var email = emailUnico();
		var nav = new Navegador(porta);
		for (int i = 0; i < 5; i++)
			assertThat(nav.login(email, "errada", "X-Forwarded-For", "203.0.113." + i).statusCode()).isEqualTo(401);

		assert429(nav.login(email, "errada", "X-Forwarded-For", "198.51.100.7"));
	}

	@Test
	void limitePorIpBarraVarreduraDeEmails() throws Exception {
		var nav = new Navegador(porta);
		for (int i = 0; i < 30; i++)
			assertThat(nav.login(emailUnico(), "errada", "X-Forwarded-For", "203.0.113.50").statusCode())
					.isEqualTo(401);

		assert429(nav.login(emailUnico(), "errada", "X-Forwarded-For", "203.0.113.50"));
		assertThat(nav.login(emailUnico(), "errada", "X-Forwarded-For", "203.0.113.51").statusCode()).isEqualTo(401);
	}

	@Test
	void ipInvalidoNoXffNaoDerruba() throws Exception {
		var email = emailUnico();
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var res = new Navegador(porta).login(email, "senha-forte-123", "X-Forwarded-For", "lixo, 999.1.1.1, <script>");
		assertThat(res.statusCode()).isEqualTo(200);
	}
}
