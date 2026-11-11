package me.clama.infra.fila;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.serializer.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import me.clama.BancoDeTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** Contexto padrao (scheduler parado): os dois schedulers do teste de cluster sao montados aqui, no mesmo banco. */
@SpringBootTest
@Import(BancoDeTeste.class)
class TarefaRecorrenteTest {

	/** Grava o modo de cada execucao e o instante agendado (unico por disparo do cron). */
	static class Gravadora extends TarefaRecorrente {
		final List<Boolean> modos = new CopyOnWriteArrayList<>();
		final List<Instant> disparos = new CopyOnWriteArrayList<>();

		Gravadora(String cron) {
			super("tarefa-de-teste", cron);
		}

		@Override
		public void executeRecurringly(TaskInstance<Void> instancia, ExecutionContext ctx) {
			if (ctx != null) disparos.add(ctx.getExecution().executionTime);
			super.executeRecurringly(instancia, ctx);
		}

		@Override
		protected void executar(boolean dryRun) {
			modos.add(dryRun);
		}
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource ds;

	@BeforeEach
	@AfterEach
	void limpar() {
		jdbc.update("delete from scheduled_tasks where task_name = 'tarefa-de-teste'");
	}

	@Test
	void dryRunPorPropriedadeEDesligadoPorPadrao() {
		var t = new Gravadora("0 0 4 * * *");
		t.setEnvironment(new MockEnvironment());
		t.executeRecurringly(null, null);
		t.setEnvironment(new MockEnvironment().withProperty("clama.tarefas.tarefa-de-teste.dry-run", "true"));
		t.executeRecurringly(null, null);
		assertThat(t.modos).containsExactly(false, true);
	}

	@Test
	void cronNoFusoDeSaoPaulo() {
		var t = new Gravadora("0 0 4 * * *");
		// 04:00 em Sao Paulo (-03) = 07:00 UTC; sem o fuso seria 04:00 na zona da JVM
		assertThat(t.agenda().getInitialExecutionTime(Instant.parse("2026-11-05T15:00:00Z")))
				.isEqualTo(Instant.parse("2026-11-06T07:00:00Z"));
	}

	/** Dois nos no mesmo banco, cron a cada segundo: cada disparo roda em um so (a linha unica da tarefa e a trava). */
	@Test
	void umaExecucaoPorDisparoNoCluster() throws Exception {
		var tarefa = new Gravadora("* * * * * *");
		tarefa.setEnvironment(new MockEnvironment());
		var a = no(tarefa, "no-a");
		var b = no(tarefa, "no-b");
		a.start();
		b.start();
		try {
			Thread.sleep(3500);
		} finally {
			a.stop();
			b.stop();
		}
		assertThat(tarefa.disparos).hasSizeGreaterThanOrEqualTo(2).doesNotHaveDuplicates();
	}

	private Scheduler no(Gravadora tarefa, String nome) {
		return Scheduler.create(ds).startTasks(tarefa).schedulerName(() -> nome).pollingInterval(Duration.ofMillis(50))
				.serializer(new Jackson3Serializer()).build();
	}
}
