package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.time.Duration;
import java.time.Instant;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
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

/** Limite absoluto de 30 dias da sessao (D15, INV:17): a sessao que desliza 7 dias nao vive para sempre. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class LimiteAbsolutoDeSessaoTest {

	static final String EXPIRADA = "Sua sessão expirou. Faça login pra continuar.";

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired LimiteDeTaxa limite;

	String email;
	Long usuario;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		if (email != null) jdbc.update("delete from usuario where email = ?", email);
	}

	Navegador logado() throws Exception {
		email = emailUnico();
		var u = Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		usuario = u.getId();
		assertThat(sessoes(usuario)).isEqualTo(1);
		return nav;
	}

	int sessoes(Long usuario) {
		return jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Integer.class, usuario.toString());
	}

	/** Recua o login da sessao: {@code idade} + {@code extra} atras. */
	void envelhecer(Duration idade) {
		jdbc.update("update spring_session set creation_time = ? where principal_name is not null",
				Instant.now().minus(idade).toEpochMilli());
	}

	@Test
	void vinteENoveDiasPassa() throws Exception {
		var nav = logado();
		envelhecer(Duration.ofDays(29));

		var res = nav.get("/api/auth/eu");

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("email").asString()).isEqualTo(email);
		assertThat(sessoes(usuario)).isEqualTo(1);
	}

	@Test
	void trintaDiasEUmSegundoDa401ComOTextoEApagaASessao() throws Exception {
		var nav = logado();
		envelhecer(Duration.ofDays(30).plusSeconds(1));

		var res = nav.get("/api/auth/eu");

		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo(EXPIRADA);
		assertThat(sessoes(usuario)).isZero();
		// sem a sessao, a proxima requisicao e a 401 comum (nao diz que expirou de novo)
		var seguinte = nav.get("/api/auth/eu");
		assertThat(seguinte.statusCode()).isEqualTo(401);
		assertThat(json(seguinte).get("mensagemPastoral").asString()).isEqualTo("Entre na sua conta para continuar.");
	}

	@Test
	void rotaPublicaSegueAnonimaSemErro() throws Exception {
		var nav = logado();
		envelhecer(Duration.ofDays(31));

		assertThat(nav.get("/api/planos").statusCode()).isNotEqualTo(401);
		assertThat(sessoes(usuario)).isZero();
	}
}
