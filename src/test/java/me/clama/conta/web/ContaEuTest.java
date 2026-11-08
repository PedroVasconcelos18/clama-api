package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import me.clama.BancoDeTeste;
import me.clama.conta.Papel;
import me.clama.conta.UsuarioRepository;
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
class ContaEuTest {

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;

	@BeforeEach
	void zerarLimites() {
		limite.limpar();
	}

	private Navegador logado(String email) throws Exception {
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	@Test
	void renomeiaEMudaOFormato() throws Exception {
		var email = emailUnico();
		var nav = logado(email);
		var res = nav.patch("/api/conta/eu", "{\"nomeCompleto\":\"Maria Nova\",\"nomeFormatoBlog\":\"completo\"}");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("nomeCompleto").asString()).isEqualTo("Maria Nova");
		assertThat(json(res).get("nomeFormatoBlog").asString()).isEqualTo("completo");
		assertThat(json(nav.get("/api/auth/eu")).get("nomeCompleto").asString()).isEqualTo("Maria Nova");
	}

	@Test
	void campoAusenteNaoMuda() throws Exception {
		var nav = logado(emailUnico());
		var res = nav.patch("/api/conta/eu", "{\"nomeFormatoBlog\":\"completo\"}");
		assertThat(json(res).get("nomeCompleto").asString()).isEqualTo("Fulana de Tal");
	}

	@Test
	void formatoInvalidoEh400ComErros() throws Exception {
		var nav = logado(emailUnico());
		var res = nav.patch("/api/conta/eu", "{\"nomeFormatoBlog\":\"bonito\"}");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("nomeFormatoBlog");
	}

	@Test
	void nomeLongoDemaisEh400() throws Exception {
		var nav = logado(emailUnico());
		var res = nav.patch("/api/conta/eu", "{\"nomeCompleto\":\"" + "a".repeat(121) + "\"}");
		assertThat(res.statusCode()).isEqualTo(400);
	}

	@Test
	void ignoraEmailEPapel() throws Exception {
		var email = emailUnico();
		var nav = logado(email);
		var res = nav.patch("/api/conta/eu",
				"{\"nomeCompleto\":\"Outra\",\"email\":\"invasor@x.com\",\"papel\":\"SUPERUSUARIO\"}");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("email").asString()).isEqualTo(email);
		assertThat(json(res).get("papel").asString()).isEqualTo("CLIENTE");
		assertThat(usuarios.findByEmail(email).orElseThrow().getPapel()).isEqualTo(Papel.CLIENTE);
	}

	@Test
	void anonimoLeva401() throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu"); // emite o XSRF-TOKEN, como a SPA
		assertThat(nav.patch("/api/conta/eu", "{}").statusCode()).isEqualTo(401);
	}
}
