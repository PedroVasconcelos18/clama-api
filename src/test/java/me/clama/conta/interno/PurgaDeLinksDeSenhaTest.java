package me.clama.conta.interno;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import me.clama.BancoDeTeste;
import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** {@code purgar-links-de-senha} (D15): vencido ha mais de 7 dias sai; o resto fica. */
@SpringBootTest
@Import(BancoDeTeste.class)
class PurgaDeLinksDeSenhaTest {

	@Autowired PurgaDeLinksDeSenha tarefa;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;

	Long usuario;

	@BeforeEach
	@AfterEach
	void limpar() {
		jdbc.update("delete from usuario where email like 'pls-%'");
	}

	@BeforeEach
	void criar() {
		usuario = Navegador.usuario(usuarios, encoder, "pls-" + Navegador.emailUnico(), "senha-forte-123", Papel.CLIENTE).getId();
	}

	void link(String hash, Duration vencidoHa, boolean usado) {
		var expira = OffsetDateTime.ofInstant(Instant.now().minus(vencidoHa), ZoneOffset.UTC);
		jdbc.update("insert into token_de_senha (usuario_id, hash, finalidade, criado_em, expira_em, usado_em) values (?, ?, 'REDEFINIR', ?, ?, ?)",
				usuario, hash, expira.minusDays(1), expira, usado ? expira.minusHours(1) : null);
	}

	int links() {
		return jdbc.queryForObject("select count(*) from token_de_senha where usuario_id = ?", Integer.class, usuario);
	}

	@Test
	void nomeECron() {
		assertThat(tarefa.getName()).isEqualTo("purgar-links-de-senha");
		assertThat(tarefa.cron()).isEqualTo("0 30 4 * * *");
		assertThat(tarefa.agenda().getInitialExecutionTime(Instant.parse("2026-11-05T00:00:00Z"))).isEqualTo(Instant.parse("2026-11-05T07:30:00Z")); // 04:30 em SP
	}

	@Test
	void seteDiasMaisUmSegundoApagaEMenosUmSegundoFica() {
		link("velho", Duration.ofDays(7).plusSeconds(1), false);
		link("velho-usado", Duration.ofDays(8), true);
		link("recente", Duration.ofDays(7).minusSeconds(1), false);
		link("ativo", Duration.ofDays(-1), false); // vence amanha

		assertThat(tarefa.executar(false)).isEqualTo("2 link(s) apagados");

		assertThat(jdbc.queryForList("select trim(hash) from token_de_senha where usuario_id = ? order by hash", String.class, usuario))
				.containsExactly("ativo", "recente");
		assertThat(tarefa.executar(false)).isEqualTo("nada a apagar");
	}

	@Test
	void dryRunSoConta() {
		link("velho", Duration.ofDays(9), false);
		link("ativo", Duration.ofDays(-1), false);

		assertThat(tarefa.executar(true)).isEqualTo("1 link(s) seriam apagados (simulação)");
		assertThat(links()).isEqualTo(2);
	}
}
