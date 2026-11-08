package me.clama.conta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.ClamaApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(BancoDeTeste.class)
class ResgateDeAdminTest {

	@Autowired ResgateDeAdmin resgate;
	@Autowired UsuarioRepository usuarios;
	@Autowired JdbcClient jdbc;

	private Usuario conta(Papel papel, boolean ativo) {
		var u = Usuario.novo("resgate-" + UUID.randomUUID() + "@exemplo.com", "Maria", papel, Instant.parse("2026-11-08T12:00:00Z"));
		if (!ativo) u.desativar();
		return usuarios.save(u);
	}

	private long auditorias(Long id) {
		return jdbc.sql("select count(*) from auditoria where entidade = 'usuario' and entidade_id = :id and acao = 'RESGATE' and autor_id is null")
				.param("id", id.toString()).query(Long.class).single();
	}

	@Test
	void promoveEReativaEAudita() {
		var u = conta(Papel.CLIENTE, false);
		resgate.resgatar(u.getEmail());
		var depois = usuarios.findById(u.getId()).orElseThrow();
		assertThat(depois.getPapel()).isEqualTo(Papel.SUPERUSUARIO);
		assertThat(depois.isAtivo()).isTrue();
		assertThat(depois.temSenha()).isFalse();
		assertThat(auditorias(u.getId())).isEqualTo(1);
	}

	@Test
	void ehIdempotente() {
		var u = conta(Papel.ADMIN, true);
		resgate.resgatar(u.getEmail());
		resgate.resgatar(u.getEmail());
		assertThat(usuarios.findById(u.getId()).orElseThrow().getPapel()).isEqualTo(Papel.SUPERUSUARIO);
		assertThat(auditorias(u.getId())).isEqualTo(1);
	}

	@Test
	void achaPeloCanonico() {
		var u = usuarios.save(Usuario.novo("ma.ria+x" + System.nanoTime() + "@gmail.com", "M", Papel.CLIENTE, Instant.now()));
		resgate.resgatar(EmailCanonico.de(u.getEmail()));
		assertThat(usuarios.findById(u.getId()).orElseThrow().getPapel()).isEqualTo(Papel.SUPERUSUARIO);
	}

	@Test
	void contaInexistenteFalhaSemMostrarOEmailEmClaro() {
		assertThatThrownBy(() -> resgate.resgatar("naoexiste-" + UUID.randomUUID() + "@dominio-sumido.org"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("dominio-sumido.org").hasMessageNotContaining("naoexiste");
	}

	@Test
	void bootFalhaSeAContaDoResgateNaoExiste() {
		try (var pg = new PostgreSQLContainer("postgres:18")) {
			pg.start();
			assertThatThrownBy(() -> new SpringApplicationBuilder(ClamaApplication.class)
					.web(org.springframework.boot.WebApplicationType.SERVLET)
					.properties("server.port=0", "spring.datasource.url=" + pg.getJdbcUrl(), "spring.datasource.username=" + pg.getUsername(),
							"spring.datasource.password=" + pg.getPassword())
					.run("--clama.conta.admin-resgate-email=ninguem@dominio-sumido.org"))
					.hasStackTraceContaining("dominio-sumido.org");
		}
	}
}
