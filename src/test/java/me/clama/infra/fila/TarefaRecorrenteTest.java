package me.clama.infra.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
		volatile RuntimeException lancar;

		Gravadora(String cron) {
			super("tarefa-de-teste", cron);
		}

		@Override
		public void executeRecurringly(TaskInstance<Void> instancia, ExecutionContext ctx) {
			if (ctx != null) disparos.add(ctx.getExecution().executionTime);
			super.executeRecurringly(instancia, ctx);
		}

		@Override
		protected String executar(boolean dryRun) {
			modos.add(dryRun);
			if (lancar != null) throw lancar;
			return "fez " + (dryRun ? "simulado" : "real");
		}
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource ds;
	@Autowired Execucoes execucoes;
	@Autowired org.springframework.transaction.PlatformTransactionManager txManager;

	@BeforeEach
	@AfterEach
	void limpar() {
		jdbc.update("delete from scheduled_tasks where task_name = 'tarefa-de-teste'");
		jdbc.update("delete from tarefa_execucao where tarefa in ('tarefa-de-teste', 'outra-tarefa')");
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

	private Gravadora comRegistro(String... propriedades) {
		var t = new Gravadora("0 0 4 * * *");
		var env = new MockEnvironment();
		for (int i = 0; i < propriedades.length; i += 2) env.setProperty(propriedades[i], propriedades[i + 1]);
		t.setEnvironment(env);
		t.setExecucoes(execucoes);
		return t;
	}

	private List<java.util.Map<String, Object>> linhas() {
		return jdbc.queryForList("select * from tarefa_execucao where tarefa = 'tarefa-de-teste' order by id");
	}

	@Test
	void disparoAgendadoGravaAExecucaoComOResumoEOModoDaConfiguracao() {
		var t = comRegistro("clama.tarefas.tarefa-de-teste.dry-run", "true");
		t.executeRecurringly(null, null);
		assertThat(linhas()).singleElement().satisfies(l -> {
			assertThat(l).containsEntry("ok", true).containsEntry("simulacao", true).containsEntry("manual", false)
					.containsEntry("resumo", "fez simulado").containsEntry("autor_id", null).containsEntry("erro", null);
		});
	}

	@Test
	void rodarGravaManualComOAutorENaoRelancaAFalha() {
		var t = comRegistro();
		t.rodar(false, 42L);
		t.lancar = new IllegalStateException("segredo@exemplo.com");
		t.rodar(true, 42L); // so registra: o job nao falha duas vezes
		assertThat(linhas()).hasSize(2);
		assertThat(linhas().get(0)).containsEntry("manual", true).containsEntry("autor_id", 42L).containsEntry("simulacao", false).containsEntry("ok", true);
		assertThat(linhas().get(1)).containsEntry("ok", false).containsEntry("simulacao", true);
	}

	@Test
	void falhaGravaSoAClasseDoErroERelancaNoDisparoAgendado() {
		var t = comRegistro();
		t.lancar = new IllegalStateException("segredo@exemplo.com");
		assertThatThrownBy(() -> t.executeRecurringly(null, null)).isSameAs(t.lancar);
		assertThat(linhas()).singleElement().satisfies(l -> {
			assertThat(l).containsEntry("ok", false).containsEntry("erro", "java.lang.IllegalStateException").containsEntry("resumo", null);
			assertThat(l.toString()).doesNotContain("segredo@exemplo.com");
		});
	}

	@Test
	void falhaParcialGravaOkFalsoComOResumoDaTarefa() {
		var t = comRegistro();
		t.lancar = new TarefaRecorrente.FalhaParcial("2 de 3 instituições, faltou 1");
		assertThatThrownBy(() -> t.executeRecurringly(null, null)).isSameAs(t.lancar);
		assertThat(linhas()).singleElement().satisfies(l -> assertThat(l).containsEntry("ok", false)
				.containsEntry("resumo", "2 de 3 instituições, faltou 1").containsEntry("erro", TarefaRecorrente.FalhaParcial.class.getName()));
	}

	@Test
	void avisoVaiAoSentryEmWarningEErroEmError() {
		var aviso = new TarefaRecorrente.FalhaParcial("abortada", new IllegalStateException("wp fora")).comoAviso();
		var parcial = new TarefaRecorrente.FalhaParcial("faltou 1");
		for (var caso : List.of(aviso, parcial)) {
			var t = comRegistro();
			t.lancar = caso;
			try (var sentry = org.mockito.Mockito.mockStatic(io.sentry.Sentry.class)) {
				assertThatThrownBy(() -> t.executeRecurringly(null, null)).isSameAs(caso);
				var captor = org.mockito.ArgumentCaptor.forClass(io.sentry.ScopeCallback.class);
				sentry.verify(() -> io.sentry.Sentry.captureException(org.mockito.ArgumentMatchers.eq(caso), captor.capture()));
				var escopo = org.mockito.Mockito.mock(io.sentry.IScope.class);
				captor.getValue().run(escopo);
				org.mockito.Mockito.verify(escopo).setLevel(caso == aviso ? io.sentry.SentryLevel.WARNING : io.sentry.SentryLevel.ERROR);
			}
		}
		assertThat(aviso.getCause()).isInstanceOf(IllegalStateException.class);
		assertThat(linhas()).hasSize(2).allSatisfy(l -> assertThat(l).containsEntry("ok", false)); // o aviso continua ok=false
	}

	@Test
	void historiaSobreviveAoRollbackDeQuemChamou() {
		var t = comRegistro();
		new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(s -> {
			t.executeRecurringly(null, null);
			s.setRollbackOnly();
		});
		assertThat(linhas()).singleElement().satisfies(l -> assertThat(l).containsEntry("ok", true));
	}

	@Test
	void cadaGravacaoApagaAsDaMesmaTarefaComMaisDeNoventaDias() {
		jdbc.update("insert into tarefa_execucao (tarefa, inicio, fim, simulacao, manual, ok) values "
				+ "('tarefa-de-teste', now() - interval '91 days', now() - interval '91 days', false, false, true),"
				+ "('tarefa-de-teste', now() - interval '89 days', now() - interval '89 days', false, false, true),"
				+ "('outra-tarefa', now() - interval '91 days', now() - interval '91 days', false, false, true)");
		comRegistro().executeRecurringly(null, null);
		assertThat(linhas()).hasSize(2);
		assertThat(jdbc.queryForObject("select count(*) from tarefa_execucao where tarefa = 'outra-tarefa'", Integer.class)).isEqualTo(1);
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
