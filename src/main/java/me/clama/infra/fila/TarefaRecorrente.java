package me.clama.infra.fila;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Instant;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Tarefa recorrente (D13): cron de 6 campos no fuso de Sao Paulo, uma execucao por disparo no cluster (a linha unica
 * {@code <nome>/recurring} em {@code scheduled_tasks} e a trava). {@code clama.tarefas.<nome>.dry-run=true} roda sem
 * alterar dados. Falha: log e Sentry, e a tarefa segue no proximo disparo.
 *
 * <p>Todo disparo, agendado ou manual ({@link #rodar}), grava uma linha em {@code tarefa_execucao} (D9): {@link #executar}
 * devolve o resumo de uma linha (sem PII); uma excecao grava {@code ok=false} com so a classe dela. Uma tarefa que termina o
 * trabalho mas falhou em parte lanca {@link FalhaParcial}.
 */
public abstract class TarefaRecorrente extends RecurringTask<Void> implements EnvironmentAware {

	public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
	private static final Logger LOG = LoggerFactory.getLogger(TarefaRecorrente.class);

	/** O trabalho terminou, mas parte falhou: a execucao grava {@code ok=false} com {@code resumo} (sem PII), nunca so o erro. */
	public static class FalhaParcial extends RuntimeException {

		private SentryLevel nivel = SentryLevel.ERROR;

		public FalhaParcial(String resumo) {
			super(resumo);
		}

		/** {@code causa} fica no log e no Sentry; o resumo da execucao so leva {@code resumo}. */
		public FalhaParcial(String resumo, Throwable causa) {
			super(resumo, causa);
		}

		/** Falha esperada (a origem esta fora do ar, nada foi gravado): o Sentry recebe WARNING, nao ERROR. A execucao segue {@code ok=false}. */
		public FalhaParcial comoAviso() {
			nivel = SentryLevel.WARNING;
			return this;
		}

		SentryLevel nivel() {
			return nivel;
		}
	}

	private final Schedule agenda;
	private final String cron;
	private Environment env;
	private Execucoes execucoes;

	protected TarefaRecorrente(String nome, String cron) {
		this(nome, cron, Schedules.cron(cron, FUSO));
	}

	private TarefaRecorrente(String nome, String cron, Schedule agenda) {
		super(nome, agenda, Void.class);
		this.agenda = agenda;
		this.cron = cron;
	}

	public String cron() {
		return cron;
	}

	/** {@code clama.tarefas.<nome>.dry-run}: o modo dos disparos agendados (padrao: real). */
	public boolean simulacaoConfigurada() {
		return env.getProperty("clama.tarefas." + getName() + ".dry-run", Boolean.class, false);
	}

	@Autowired(required = false)
	void setExecucoes(Execucoes execucoes) {
		this.execucoes = execucoes;
	}

	public Schedule agenda() {
		return agenda;
	}

	/** {@code dryRun}: so contar e registrar no log o que faria. Devolve uma linha de resumo, sem PII ({@link FalhaParcial} se falhou em parte). */
	protected abstract String executar(boolean dryRun);

	@Override
	public void setEnvironment(Environment env) {
		this.env = env;
	}

	@Override
	public void executeRecurringly(TaskInstance<Void> instancia, ExecutionContext ctx) {
		disparar(simulacaoConfigurada(), false, null);
	}

	/** "Rodar agora": mesma execucao e mesmo registro, mas uma falha so e registrada (o job nao falha duas vezes). */
	public void rodar(boolean simulacao, Long autorId) {
		disparar(simulacao, true, autorId);
	}

	private void disparar(boolean dryRun, boolean manual, Long autorId) {
		var inicio = Instant.now();
		try {
			var resumo = executar(dryRun);
			registrar(inicio, dryRun, manual, autorId, true, resumo, null);
		} catch (RuntimeException e) {
			var nivel = e instanceof FalhaParcial f ? f.nivel() : SentryLevel.ERROR;
			if (nivel == SentryLevel.WARNING) {
				LOG.warn("Tarefa recorrente {} falhou", getName(), e);
			} else {
				LOG.error("Tarefa recorrente {} falhou", getName(), e);
			}
			Sentry.captureException(e, scope -> scope.setLevel(nivel));
			registrar(inicio, dryRun, manual, autorId, false, e instanceof FalhaParcial ? e.getMessage() : null, e.getClass().getName());
			if (!manual) throw e;
		}
	}

	/** Falha ao registrar nao derruba a tarefa: so vai ao log. */
	private void registrar(Instant inicio, boolean dryRun, boolean manual, Long autorId, boolean ok, String resumo, String erro) {
		if (execucoes == null) return;
		try {
			execucoes.gravar(getName(), inicio, Instant.now(), dryRun, manual, autorId, ok, resumo, erro);
		} catch (RuntimeException e) {
			LOG.error("Tarefa recorrente {}: nao gravou a execucao", getName(), e);
		}
	}
}
