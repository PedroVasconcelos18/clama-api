package me.clama.infra.fila;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import io.sentry.Sentry;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Tarefa recorrente (D13): cron de 6 campos no fuso de Sao Paulo, uma execucao por disparo no cluster (a linha unica
 * {@code <nome>/recurring} em {@code scheduled_tasks} e a trava). {@code clama.tarefas.<nome>.dry-run=true} roda sem
 * alterar dados. Falha: log e Sentry, e a tarefa segue no proximo disparo.
 */
public abstract class TarefaRecorrente extends RecurringTask<Void> implements EnvironmentAware {

	public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
	private static final Logger LOG = LoggerFactory.getLogger(TarefaRecorrente.class);

	private final Schedule agenda;
	private Environment env;

	protected TarefaRecorrente(String nome, String cron) {
		this(nome, Schedules.cron(cron, FUSO));
	}

	private TarefaRecorrente(String nome, Schedule agenda) {
		super(nome, agenda, Void.class);
		this.agenda = agenda;
	}

	public Schedule agenda() {
		return agenda;
	}

	/** {@code dryRun}: so contar e registrar no log o que faria. */
	protected abstract void executar(boolean dryRun);

	@Override
	public void setEnvironment(Environment env) {
		this.env = env;
	}

	@Override
	public void executeRecurringly(TaskInstance<Void> instancia, ExecutionContext ctx) {
		var dryRun = env.getProperty("clama.tarefas." + getName() + ".dry-run", Boolean.class, false);
		try {
			executar(dryRun);
		} catch (RuntimeException e) {
			LOG.error("Tarefa recorrente {} falhou", getName(), e);
			Sentry.captureException(e);
			throw e;
		}
	}
}
