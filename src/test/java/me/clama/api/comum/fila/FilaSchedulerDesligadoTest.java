package me.clama.api.comum.fila;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** Perfil test: scheduler desligado. O enfileirar grava a linha, mas este contexto nao executa nada. */
@SpringBootTest
@Import({BancoDeTeste.class, FilaSchedulerDesligadoTest.Config.class})
@ActiveProfiles("test")
class FilaSchedulerDesligadoTest {
	record P(String v) {}

	static final AtomicInteger EXECUCOES = new AtomicInteger();

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {
		@Bean
		TipoDeJob<P> jobDesligado() {
			return new TipoDeJob<>() {
				public String nome() { return "job-desligado"; }
				public Class<P> payload() { return P.class; }
				public Duration esperaEntreTentativas() { return Duration.ofMillis(100); }
				public void executar(P p) { EXECUCOES.incrementAndGet(); }
			};
		}
	}

	@Autowired Fila fila;
	@Autowired TipoDeJob<P> jobDesligado;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;
	@Autowired ApplicationContext ctx;

	@BeforeEach
	void limpar() {
		BancoDeTeste.exigirContainer(jdbc);
		jdbc.update("delete from scheduled_tasks where task_name = 'job-desligado'");
		EXECUCOES.set(0);
	}

	@Test
	void semSchedulerEnfileiraMasNaoExecuta() throws Exception {
		assertThat(ctx.getBeanNamesForType(com.github.kagkarlsson.scheduler.Scheduler.class)).isEmpty();
		tx.executeWithoutResult(s -> assertThat(fila.enfileirar(jobDesligado, "x/1", new P("a"), Instant.now())).isTrue());
		Thread.sleep(1500); // 15 ciclos de polling de 100 ms
		assertThat(EXECUCOES.get()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = 'job-desligado'", Integer.class))
				.isEqualTo(1);
	}
}
