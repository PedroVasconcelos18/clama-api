package me.clama.api.comum.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
@SchedulerLigado
class FilaTest {
	record JobDeTeste(String valor) {}

	/** Handler programavel: falha as primeiras N vezes. */
	static class Recorder implements TipoDeJob<JobDeTeste> {
		final List<String> recebidos = new CopyOnWriteArrayList<>();
		final List<Integer> retentativas = new CopyOnWriteArrayList<>();
		final List<Long> instantes = new CopyOnWriteArrayList<>();
		final List<Object> esgotou = new CopyOnWriteArrayList<>();
		final AtomicInteger chamadas = new AtomicInteger();
		volatile int falhas;
		volatile boolean esgotouEstoura;
		volatile java.util.function.Consumer<JobDeTeste> aoExecutar = p -> {};
		volatile java.util.function.Consumer<JobDeTeste> aoEsgotar = p -> {};

		public String nome() { return "job-de-teste"; }
		public Class<JobDeTeste> payload() { return JobDeTeste.class; }
		public Duration esperaEntreTentativas() { return Duration.ofMillis(100); }

		public void executar(JobDeTeste p, int retentativa) {
			instantes.add(System.nanoTime());
			aoExecutar.accept(p);
			retentativas.add(retentativa);
			int n = chamadas.incrementAndGet();
			recebidos.add(p.valor());
			if (n <= falhas) {
				throw new IllegalStateException("falha " + n);
			}
		}

		public void esgotou(JobDeTeste p, Throwable erro) {
			esgotou.add(p);
			esgotou.add(erro);
			aoEsgotar.accept(p);
			if (esgotouEstoura) throw new IllegalStateException("esgotou quebrou");
		}
	}

	/** O fator e lido uma vez, quando a Task e montada no boot: precisa de outro tipo. */
	static class Backoff extends Recorder {
		public String nome() { return "job-backoff"; }
		public double fatorDeBackoff() { return 2.0; }
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {
		@Bean
		Recorder recorder() { return new Recorder(); }

		@Bean
		Backoff backoff() { return new Backoff(); }
	}

	@Autowired Fila fila;
	@Autowired @org.springframework.beans.factory.annotation.Qualifier("recorder") Recorder job;
	@Autowired Backoff backoff;
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
		backoff.instantes.clear();
		backoff.falhas = 0;
		job.esgotouEstoura = false;
		job.aoExecutar = p -> {};
		job.aoEsgotar = p -> {};
		job.retentativas.clear();
		job.instantes.clear();
		job.esgotou.clear();
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
	void instanciaDeterministicaNaoDuplica() {
		boolean[] r = new boolean[3];
		tx.executeWithoutResult(s -> {
			r[0] = fila.enfileirar(job, "gerar/1", new JobDeTeste("a"));
			r[1] = fila.enfileirar(job, "gerar/1", new JobDeTeste("b"), Instant.now());
			r[2] = fila.enfileirar(job, "gerar/2", new JobDeTeste("c"));
		});
		assertThat(r).containsExactly(true, false, true);
		await().atMost(Duration.ofSeconds(5)).until(() -> job.chamadas.get() == 2);
		assertThat(job.recebidos).containsExactlyInAnyOrder("a", "c");
	}

	@Test
	void duplicadoNaoQuebraATransacaoDoChamador() {
		tx.executeWithoutResult(s -> fila.enfileirar(job, "gerar/1", new JobDeTeste("a"), Instant.now().plusSeconds(60)));
		tx.executeWithoutResult(s -> {
			assertThat(fila.enfileirar(job, "gerar/1", new JobDeTeste("b"))).isFalse();
			// a transacao segue utilizavel e commita
			assertThat(fila.enfileirar(job, "gerar/2", new JobDeTeste("c"), Instant.now().plusSeconds(60))).isTrue();
			jdbc.queryForObject("select 1", Integer.class);
		});
		assertThat(linhas()).isEqualTo(2);
	}

	@Test
	void duplicadoComRollbackDoPrimeiroNaoFicaPreso() {
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			fila.enfileirar(job, "gerar/1", new JobDeTeste("a"));
			throw new IllegalArgumentException("rollback");
		})).isInstanceOf(IllegalArgumentException.class);
		tx.executeWithoutResult(s -> assertThat(fila.enfileirar(job, "gerar/1", new JobDeTeste("b"))).isTrue());
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job.recebidos).containsExactly("b"));
	}

	@Test
	void enfileirarConcorrenteMesmaInstancia() throws Exception {
		var primeiroEnfileirou = new java.util.concurrent.CountDownLatch(1);
		var segundoComecou = new java.util.concurrent.CountDownLatch(1);
		Instant longe = Instant.now().plusSeconds(60);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var f1 = pool.submit(() -> tx.execute(s -> {
				boolean r = fila.enfileirar(job, "gerar/c", new JobDeTeste("a"), longe);
				fila.enfileirar(job, "outro/1", new JobDeTeste("o1"), longe);
				primeiroEnfileirou.countDown();
				aguardar(segundoComecou);
				dormir(500); // segura a transacao aberta: o segundo tem que esperar o commit
				return r;
			}));
			var f2 = pool.submit(() -> {
				aguardar(primeiroEnfileirou);
				return tx.execute(s -> {
					segundoComecou.countDown();
					boolean r = fila.enfileirar(job, "gerar/c", new JobDeTeste("b"), longe);
					fila.enfileirar(job, "outro/2", new JobDeTeste("o2"), longe);
					return r;
				});
			});
			assertThat(List.of(f1.get(15, java.util.concurrent.TimeUnit.SECONDS), f2.get(15, java.util.concurrent.TimeUnit.SECONDS)))
					.containsExactly(true, false);
		} finally {
			pool.shutdownNow();
		}
		assertThat(linhas()).isEqualTo(3); // gerar/c, outro/1, outro/2: as duas transacoes commitaram
	}

	@Test
	void enfileirarMesmaInstanciaDuranteExecucaoRetornaFalse() throws Exception {
		var rodando = new java.util.concurrent.CountDownLatch(1);
		var libera = new java.util.concurrent.CountDownLatch(1);
		job.aoExecutar = p -> {
			rodando.countDown();
			aguardar(libera);
		};
		tx.executeWithoutResult(s -> fila.enfileirar(job, "exec/1", new JobDeTeste("a")));
		assertThat(rodando.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		// a linha existe ate o OneTimeTask terminar: reenfileirar durante a execucao e no-op
		Boolean r = tx.execute(s -> fila.enfileirar(job, "exec/1", new JobDeTeste("b")));
		assertThat(r).isFalse();
		libera.countDown();
		await().atMost(Duration.ofSeconds(5)).until(() -> linhas() == 0);
		assertThat(job.recebidos).containsExactly("a");
	}

	@Test
	void reagendarMesmaInstanciaDeDentroDoEsgotouFunciona() {
		job.falhas = 4; // 1 + 3 retentativas falham; a 5a execucao (reagendada) passa
		var reagendou = new java.util.concurrent.atomic.AtomicReference<Boolean>();
		job.aoEsgotar = p -> reagendou.set(tx.execute(s -> fila.enfileirar(job, "re/1", new JobDeTeste("de-novo"), Instant.now().plusMillis(200))));
		tx.executeWithoutResult(s -> fila.enfileirar(job, "re/1", new JobDeTeste("a")));
		await().atMost(Duration.ofSeconds(15)).until(() -> job.chamadas.get() == 5 && linhas() == 0);
		assertThat(reagendou.get()).isTrue();
		assertThat(job.recebidos).containsExactly("a", "a", "a", "a", "de-novo");
	}

	private static void aguardar(java.util.concurrent.CountDownLatch l) {
		try {
			if (!l.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("latch");
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void dormir(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void enfileirarSemTransacaoComInstanciaFalha() {
		assertThatThrownBy(() -> fila.enfileirar(job, "gerar/1", new JobDeTeste("x"))).isInstanceOf(IllegalStateException.class);
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
		verify(observador, timeout(10_000)).falhou(eq("job-de-teste"), anyString(), any(IllegalStateException.class));
		Thread.sleep(1000);
		assertThat(job.chamadas.get()).isEqualTo(4);
		verify(observador, org.mockito.Mockito.times(1)).falhou(any(), any(), any());
		assertThat(linhas()).isZero();
		assertThat(job.retentativas).containsExactly(0, 1, 2, 3);
		assertThat(job.esgotou).hasSize(2);
		assertThat(job.esgotou.get(0)).isEqualTo(new JobDeTeste("x"));
		assertThat((Throwable) job.esgotou.get(1)).isInstanceOf(IllegalStateException.class).hasMessage("falha 4");
	}

	@Test
	void esgotouQueEstouraNaoRoubaOObservadorNemReexecuta() throws Exception {
		job.falhas = Integer.MAX_VALUE;
		job.esgotouEstoura = true;
		tx.executeWithoutResult(s -> fila.enfileirar(job, new JobDeTeste("x")));
		verify(observador, timeout(10_000)).falhou(eq("job-de-teste"), anyString(), any(IllegalStateException.class));
		Thread.sleep(1500);
		assertThat(job.chamadas.get()).isEqualTo(4);
		assertThat(job.esgotou).hasSize(2);
		verify(observador, org.mockito.Mockito.times(1)).falhou(any(), any(), any());
		assertThat(linhas()).isZero();
	}

	@Test
	void backoffExponencial() {
		backoff.falhas = Integer.MAX_VALUE;
		tx.executeWithoutResult(s -> fila.enfileirar(backoff, new JobDeTeste("x")));
		verify(observador, timeout(10_000)).falhou(eq("job-backoff"), anyString(), any(IllegalStateException.class));
		List<Long> esperasMs = new java.util.ArrayList<>();
		for (int i = 1; i < backoff.instantes.size(); i++) {
			esperasMs.add((backoff.instantes.get(i) - backoff.instantes.get(i - 1)) / 1_000_000);
		}
		// espera agendada + ate 1 ciclo de polling (100 ms) + folga
		assertThat(esperasMs).hasSize(3);
		long[] base = {100, 200, 400};
		for (int i = 0; i < 3; i++) {
			assertThat(esperasMs.get(i)).as("espera %d: %s", i, esperasMs).isBetween(base[i] - 10, base[i] + 250);
		}
	}

	@Test
	void shutdownEsperaOsJobsEmAndamento(@Autowired com.github.kagkarlsson.scheduler.Scheduler scheduler) {
		assertThat(org.springframework.test.util.ReflectionTestUtils.getField(scheduler, "shutdownMaxWait"))
				.isEqualTo(Duration.ofSeconds(25));
	}

	@Test
	void retentaEPassa() throws Exception {
		job.falhas = 2;
		tx.executeWithoutResult(s -> fila.enfileirar(job, new JobDeTeste("x")));
		await().atMost(Duration.ofSeconds(10)).until(() -> job.chamadas.get() == 3 && linhas() == 0);
		Thread.sleep(500);
		assertThat(job.chamadas.get()).isEqualTo(3);
		verify(observador, never()).falhou(any(), any(), any());
		assertThat(job.retentativas).containsExactly(0, 1, 2);
		assertThat(job.esgotou).isEmpty();
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
