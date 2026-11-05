package me.clama.api.comum.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Import({BancoDeTeste.class, FilaTest.Config.class})
@ActiveProfiles("test")
class FilaTest {
	record JobDeTeste(String valor) {}

	/** Handler programavel: falha as primeiras N vezes. */
	static class Recorder implements TipoDeJob<JobDeTeste> {
		final List<String> recebidos = new CopyOnWriteArrayList<>();
		final AtomicInteger chamadas = new AtomicInteger();
		volatile int falhas;

		public String nome() { return "job-de-teste"; }
		public Class<JobDeTeste> payload() { return JobDeTeste.class; }
		public Duration esperaEntreTentativas() { return Duration.ofMillis(100); }

		public void executar(JobDeTeste p) {
			int n = chamadas.incrementAndGet();
			recebidos.add(p.valor());
			if (n <= falhas) {
				throw new IllegalStateException("falha " + n);
			}
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {
		@Bean
		Recorder recorder() { return new Recorder(); }
	}

	@Autowired Fila fila;
	@Autowired Recorder job;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean ObservadorDeFalhaDeJob observador;

	@BeforeEach
	void limpar() {
		BancoDeTeste.exigirContainer(jdbc);
		jdbc.update("delete from scheduled_tasks");
		job.recebidos.clear();
		job.chamadas.set(0);
		job.falhas = 0;
	}

	private int linhas() {
		return jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class);
	}

	@Test
	void jobRodaDepoisDoCommit() {
		tx.executeWithoutResult(s -> fila.enfileirar(job, new JobDeTeste("x")));
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job.recebidos).containsExactly("x"));
	}

	@Test
	void rollbackDescartaJob() throws Exception {
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			fila.enfileirar(job, new JobDeTeste("x"));
			throw new IllegalArgumentException("rollback");
		})).isInstanceOf(IllegalArgumentException.class);
		Thread.sleep(2000);
		assertThat(job.chamadas.get()).isZero();
		assertThat(linhas()).isZero();
	}

	@Test
	void enfileirarSemTransacaoFalha() {
		assertThatThrownBy(() -> fila.enfileirar(job, new JobDeTeste("x"))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> fila.enfileirar(job, new JobDeTeste("x"), Instant.now()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void retentaAteOMaximoEDesiste() throws Exception {
		job.falhas = Integer.MAX_VALUE;
		tx.executeWithoutResult(s -> fila.enfileirar(job, new JobDeTeste("x")));
		verify(observador, timeout(10_000)).falhou(eq("job-de-teste"), any(IllegalStateException.class));
		Thread.sleep(1000);
		assertThat(job.chamadas.get()).isEqualTo(4);
		verify(observador, org.mockito.Mockito.times(1)).falhou(any(), any());
		assertThat(linhas()).isZero();
	}

	@Test
	void retentaEPassa() throws Exception {
		job.falhas = 2;
		tx.executeWithoutResult(s -> fila.enfileirar(job, new JobDeTeste("x")));
		await().atMost(Duration.ofSeconds(10)).until(() -> job.chamadas.get() == 3 && linhas() == 0);
		Thread.sleep(500);
		assertThat(job.chamadas.get()).isEqualTo(3);
		verify(observador, never()).falhou(any(), any());
	}

	@Test
	void agendadoPraDepoisRodaDepois() throws Exception {
		Instant quando = Instant.now().plusSeconds(1);
		tx.executeWithoutResult(s -> fila.enfileirar(job, new JobDeTeste("x"), quando));
		Thread.sleep(700);
		assertThat(job.chamadas.get()).isZero();
		await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(job.recebidos).containsExactly("x"));
		assertThat(Instant.now()).isAfterOrEqualTo(quando);
	}

	@Test
	void jobOrfaoEhRetomado() {
		// processo que morreu: picked=true com heartbeat de ha 1 hora
		jdbc.update("""
				insert into scheduled_tasks (task_name, task_instance, task_data, execution_time, picked, picked_by,
				  last_heartbeat, version)
				values ('job-de-teste', 'orfao', convert_to('{"valor":"orfao"}', 'UTF8'), now() - interval '1 hour', true,
				  'morto', now() - interval '1 hour', 1)
				""");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(job.recebidos).containsExactly("orfao"));
	}

	@Test
	void flywayNaoAlteraTabelasDoDjango() {
		String sql = """
				select table_name, column_name, data_type, is_nullable, column_default, character_maximum_length
				from information_schema.columns where table_schema = 'public'
				  and table_name not in ('scheduled_tasks', 'flyway_schema_history')
				order by table_name, ordinal_position
				""";
		List<String> depois = jdbc.query(sql, (rs, i) -> linha(rs));
		List<String> tabelasDepois = jdbc.queryForList(
				"select table_name from information_schema.tables where table_schema = 'public' order by 1", String.class);
		try (var puro = new PostgreSQLContainer(DockerImageName.parse("postgres:18")).withInitScript("db/schema-prod.sql")) {
			puro.start();
			var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
					puro.getJdbcUrl(), puro.getUsername(), puro.getPassword());
			var jdbcPuro = new JdbcTemplate(ds);
			assertThat(jdbcPuro.query(sql, (rs, i) -> linha(rs))).isNotEmpty().isEqualTo(depois);
			List<String> tabelasAntes = jdbcPuro.queryForList(
					"select table_name from information_schema.tables where table_schema = 'public' order by 1", String.class);
			assertThat(tabelasDepois).containsExactlyInAnyOrderElementsOf(
					java.util.stream.Stream.concat(tabelasAntes.stream(), java.util.stream.Stream.of("scheduled_tasks", "flyway_schema_history")).toList());
		}
	}

	private static String linha(java.sql.ResultSet rs) throws java.sql.SQLException {
		return String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
				String.valueOf(rs.getString(5)), String.valueOf(rs.getString(6)));
	}
}
