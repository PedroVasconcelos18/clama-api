package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

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

/** Hierarquia SUPERUSUARIO > ADMIN > CLIENTE nas regras da cadeia (hasRole) e no @PreAuthorize. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class PapeisTest {

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;

	@BeforeEach
	void zerarLimites() {
		limite.limpar();
	}

	private Navegador logado(Papel papel) throws Exception {
		var email = emailUnico();
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", papel);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	@Test
	void superusuarioAcessaAdmin() throws Exception {
		var nav = logado(Papel.SUPERUSUARIO);
		assertThat(nav.get("/api/admin/teste").statusCode()).isEqualTo(200);
		assertThat(nav.get("/api/admin/teste/so-superusuario").statusCode()).isEqualTo(200);
	}

	@Test
	void adminAcessaAdminMasNaoORestritoAoSuperusuario() throws Exception {
		var nav = logado(Papel.ADMIN);
		assertThat(nav.get("/api/admin/teste").statusCode()).isEqualTo(200);
		assertThat(nav.get("/api/admin/teste/so-superusuario").statusCode()).isEqualTo(403);
	}

	@Test
	void clienteLeva403NoAdmin() throws Exception {
		var nav = logado(Papel.CLIENTE);
		assertThat(nav.get("/api/admin/teste").statusCode()).isEqualTo(403);
		assertThat(nav.get("/api/auth/eu").statusCode()).isEqualTo(200);
	}
}
