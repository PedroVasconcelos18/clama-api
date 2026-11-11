package me.clama.infra.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.Scheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import me.clama.BancoDeTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({ BancoDeTeste.class, FilaTest.Jobs.class })
@FilaLigada
class FilaTest {

	record Dado(String valor) {}

	/** Job programavel: falha as primeiras {@code falhas} execucoes. */
	static class Gravador extends TipoDeJob<Dado> {
		final List<String> recebidos = new CopyOnWriteArrayList<>();
		final List<Integer> tentativas = new CopyOnWriteArrayList<>();
		final List<Long> instantes = new CopyOnWriteArrayList<>();
		final List<Object> desistencias = new CopyOnWriteArrayList<>();
		final AtomicInteger chamadas = new AtomicInteger();
		volatile int falhas;
		volatile Consumer<Dado> aoExecutar = d -> {};

		Gravador(String nome, Retentativas politica) {
			super(nome, Dado.class, politica);
		}

		@Override
		protected void executar(Dado d, int tentativa) {
			instantes.add(System.nanoTime());
			aoExecutar.accept(d);
			tentativas.add(tentativa);
			recebidos.add(d.valor());
			int n = chamadas.incrementAndGet();
			if (n <= falhas) throw new IllegalStateException("falha " + n);
		}

		@Override
		protected void aoDesistir(Dado d, Throwable erro) {
			desistencias.add(d);
			desistencias.add(erro);
		}

		void zerar() {
			recebidos.clear();
			tentativas.clear();
			instantes.clear();
			desistencias.clear();
			chamadas.set(0);
			falhas = 0;
			aoExecutar = d -> {};
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Jobs {
		@Bean
		Gravador job() {
			return new Gravador("job-de-teste", new TipoDeJob.Retentativas(3, Duration.ofMillis(100), 1.0));
		}

		@Bean
		Gravador backoff() {
			return new Gravador("job-backoff", new TipoDeJob.Retentativas(3, Duration.ofMillis(100), 2.0));
		}
	}

	@Autowired Fila fila;
	@Autowired @Qualifier("job") Gravador job;
	@Autowired @Qualifier("backoff") Gravador backoff;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	@AfterEach
	void limpar() {
		job.zerar();
		backoff.zerar();
	}

	int linhas() {
		return jdbc.queryForObject("select count(*) from scheduled_tasks where task_name like 'job-%'", Integer.class);
	}

	void enfileirar(String instancia, String valor, Instant quando) {
		tx.executeWithoutResult(s -> fila.enfileirar(job, instancia, new Dado(valor), quando));
	}

	@Test
	void soFicaVisivelDepoisDoCommit() throws Exception {
		var antesDoCommit = new int[1];
		tx.executeWithoutResult(s -> {
			assertThat(fila.enfileirar(job, job.instancia(1), new Dado("x"), Instant.now())).isTrue();
			// outra conexao, fora da transacao: ainda nao ve a linha
			try (var outra = Executors.newSingleThreadExecutor()) {
				antesDoCommit[0] = outra.submit(this::linhas).get();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		assertThat(antesDoCommit[0]).isZero();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job.recebidos).containsExactly("x"));
		await().atMost(Duration.ofSeconds(5)).until(() -> linhas() == 0);
	}

	@Test
	void rollbackDescartaOJob() throws Exception {
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			fila.enfileirar(job, job.instancia(1), new Dado("x"), Instant.now());
			throw new IllegalArgumentException("rollback");
		})).isInstanceOf(IllegalArgumentException.class);
		Thread.sleep(1000); // 10 ciclos de polling
		assertThat(job.chamadas.get()).isZero();
		assertThat(linhas()).isZero();
	}

	@Test
	void instanciaEOTipoMaisOId() {
		assertThat(job.instancia(42)).isEqualTo("job-de-teste/42");
	}

	@Test
	void payloadVaiEmJson() {
		enfileirar("j/1", "x", Instant.now().plusSeconds(60));
		assertThat(jdbc.queryForObject("select convert_from(task_data, 'UTF8') from scheduled_tasks where task_instance = 'j/1'",
				String.class)).isEqualTo("{\"valor\":\"x\"}");
	}

	@Test
	void duplicadaENoOpENaoQuebraATransacao() {
		enfileirar("j/1", "a", Instant.now().plusSeconds(60));
		var r = new ArrayList<Boolean>();
		tx.executeWithoutResult(s -> {
			r.add(fila.enfileirar(job, "j/1", new Dado("b"), Instant.now()));
			r.add(fila.enfileirar(job, "j/2", new Dado("c"), Instant.now().plusSeconds(60)));
			jdbc.queryForObject("select 1", Integer.class); // a transacao segue utilizavel
		});
		assertThat(r).containsExactly(false, true);
		assertThat(linhas()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select convert_from(task_data, 'UTF8') from scheduled_tasks where task_instance = 'j/1'",
				String.class)).contains("\"a\"");
	}

	@Test
	void duplicadaConcorrenteEsperaOCommitDaPrimeira() throws Exception {
		var primeiroEnfileirou = new CountDownLatch(1);
		var segundoComecou = new CountDownLatch(1);
		Instant longe = Instant.now().plusSeconds(60);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var f1 = pool.submit(() -> tx.execute(s -> {
				boolean r = fila.enfileirar(job, "j/c", new Dado("a"), longe);
				fila.enfileirar(job, "outro/1", new Dado("o1"), longe);
				primeiroEnfileirou.countDown();
				aguardar(segundoComecou);
				dormir(500); // segura a transacao aberta: o segundo espera o commit
				return r;
			}));
			var f2 = pool.submit(() -> {
				aguardar(primeiroEnfileirou);
				return tx.execute(s -> {
					segundoComecou.countDown();
					boolean r = fila.enfileirar(job, "j/c", new Dado("b"), longe);
					fila.enfileirar(job, "outro/2", new Dado("o2"), longe);
					return r;
				});
			});
			assertThat(List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS))).containsExactly(true, false);
		}
		assertThat(linhas()).isEqualTo(3); // as duas transacoes commitaram
	}

	@Test
	void enfileirarSemTransacaoFalha() {
		assertThatThrownBy(() -> fila.enfileirar(job, "j/1", new Dado("x"), Instant.now()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void retentaEPassa() throws Exception {
		job.falhas = 2;
		enfileirar("j/1", "x", Instant.now());
		await().atMost(Duration.ofSeconds(10)).until(() -> job.chamadas.get() == 3 && linhas() == 0);
		Thread.sleep(300);
		assertThat(job.tentativas).containsExactly(0, 1, 2);
		assertThat(job.desistencias).isEmpty();
	}

	@Test
	void retentaAteOMaximoEDesisteUmaVez() throws Exception {
		job.falhas = Integer.MAX_VALUE;
		enfileirar("j/1", "x", Instant.now());
		await().atMost(Duration.ofSeconds(10)).until(() -> !job.desistencias.isEmpty());
		Thread.sleep(500);
		assertThat(job.tentativas).containsExactly(0, 1, 2, 3);
		assertThat(job.desistencias).hasSize(2);
		assertThat(job.desistencias.get(0)).isEqualTo(new Dado("x"));
		assertThat((Throwable) job.desistencias.get(1)).isInstanceOf(IllegalStateException.class).hasMessage("falha 4");
		assertThat(linhas()).isZero();
	}

	@Test
	void backoffExponencial() {
		backoff.falhas = Integer.MAX_VALUE;
		tx.executeWithoutResult(s -> fila.enfileirar(backoff, "b/1", new Dado("x"), Instant.now()));
		await().atMost(Duration.ofSeconds(10)).until(() -> !backoff.desistencias.isEmpty());
		var esperas = new ArrayList<Long>();
		for (int i = 1; i < backoff.instantes.size(); i++)
			esperas.add((backoff.instantes.get(i) - backoff.instantes.get(i - 1)) / 1_000_000);
		long[] base = { 100, 200, 400 };
		assertThat(esperas).hasSize(3);
		for (int i = 0; i < 3; i++) // espera agendada + ate 1 ciclo de polling (100 ms) + folga
			assertThat(esperas.get(i)).as("espera %d: %s", i, esperas).isBetween(base[i] - 10, base[i] + 250);
	}

	/** Processo que morreu no meio: picked=true e heartbeat velho. O detector de execucoes mortas devolve o job a fila. */
	@Test
	void execucaoOrfaERetomada() {
		jdbc.update("""
				insert into scheduled_tasks (task_name, task_instance, task_data, execution_time, picked, picked_by,
				  last_heartbeat, version)
				values ('job-de-teste', 'orfao', convert_to('{"valor":"orfao"}', 'UTF8'), now() - interval '1 hour', true,
				  'morto', now() - interval '1 hour', 1)""");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(job.recebidos).containsExactly("orfao"));
	}

	@Test
	void existePendenteIncluiAgendadoEEmExecucao() throws Exception {
		assertThat(fila.existePendente("job-de-teste", "j/1")).isFalse();
		enfileirar("j/1", "a", Instant.now().plusSeconds(60));
		assertThat(fila.existePendente("job-de-teste", "j/1")).isTrue();
		assertThat(fila.existePendente("job-backoff", "j/1")).isFalse();

		var rodando = new CountDownLatch(1);
		var libera = new CountDownLatch(1);
		job.aoExecutar = d -> {
			rodando.countDown();
			aguardar(libera);
		};
		enfileirar("j/2", "b", Instant.now());
		assertThat(rodando.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(fila.existePendente("job-de-teste", "j/2")).isTrue();
		libera.countDown();
		await().atMost(Duration.ofSeconds(5)).until(() -> !fila.existePendente("job-de-teste", "j/2"));
	}

	@Test
	void anteciparTrazParaAgora() {
		enfileirar("j/1", "a", Instant.now().plusSeconds(300));
		assertThat(tx.<Boolean>execute(s -> fila.antecipar("job-de-teste", "j/1"))).isTrue();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job.recebidos).containsExactly("a"));
	}

	@Test
	void anteciparInexistenteOuEmExecucaoRetornaFalse() throws Exception {
		assertThat(tx.<Boolean>execute(s -> fila.antecipar("job-de-teste", "j/nada"))).isFalse();
		var rodando = new CountDownLatch(1);
		var libera = new CountDownLatch(1);
		job.aoExecutar = d -> {
			rodando.countDown();
			aguardar(libera);
		};
		enfileirar("j/1", "a", Instant.now());
		assertThat(rodando.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(tx.<Boolean>execute(s -> fila.antecipar("job-de-teste", "j/1"))).isFalse();
		libera.countDown();
		await().atMost(Duration.ofSeconds(5)).until(() -> linhas() == 0);
		assertThat(job.recebidos).containsExactly("a");
	}

	@Test
	void anteciparSemTransacaoFalha() {
		assertThatThrownBy(() -> fila.antecipar("job-de-teste", "j/1")).isInstanceOf(IllegalStateException.class);
	}

	/**
	 * O stop() do db-scheduler roda no destroy do bean, depois das fases de lifecycle (o graceful do Tomcat): espera
	 * shutdown-max-wait, interrompe e espera de novo, e os executores utilitarios levam ate ~6 s. Tudo dentro dos 60 s de
	 * draining do Railway.
	 */
	@Test
	void shutdownCabeNoDraining(@Autowired Scheduler scheduler,
			@Value("${spring.lifecycle.timeout-per-shutdown-phase}") Duration fase) {
		var espera = (Duration) ReflectionTestUtils.getField(scheduler, "shutdownMaxWait");
		assertThat(espera).isEqualTo(Duration.ofSeconds(15));
		assertThat(fase.plus(espera.multipliedBy(2)).plusSeconds(6).plus(me.clama.infra.email.EnvioAssincrono.ESPERA_NO_DESLIGAMENTO))
				.isLessThanOrEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void tipoQueNaoEBeanERecusado() {
		var avulso = new Gravador("avulso", TipoDeJob.Retentativas.NENHUMA);
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> fila.enfileirar(avulso, "a/1", new Dado("x"), Instant.now())))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("avulso");
	}

	static void aguardar(CountDownLatch l) {
		try {
			if (!l.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch");
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	static void dormir(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}
}
