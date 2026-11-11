package me.clama.infra.fila;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import io.sentry.Sentry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Um tipo de job da fila (D13): cada modulo declara os seus como {@code @Component}, e o db-scheduler os registra.
 * Enfileira-se pela {@link Fila}, com instancia deterministica ({@link #instancia}).
 *
 * <p><b>Payload so leva IDs</b>: ele fica em JSON puro em {@code scheduled_tasks.task_data}; nada de PII nem texto.
 */
public abstract class TipoDeJob<P> extends OneTimeTask<P> {

	private static final Logger LOG = LoggerFactory.getLogger(TipoDeJob.class);

	/**
	 * Retentativas depois da 1a falha; a espera antes da retentativa n e {@code espera * fator^(n-1)} (fator 1 = fixa,
	 * 2 = 30, 60, 120 s).
	 */
	public record Retentativas(int maximo, Duration espera, double fator) {
		public static final Retentativas NENHUMA = new Retentativas(0, Duration.ofSeconds(1), 1.0);
	}

	private final FailureHandler<P> aoFalhar;

	protected TipoDeJob(String nome, Class<P> payload, Retentativas politica) {
		super(nome, payload);
		aoFalhar = FailureHandler.<P>maxRetries(politica.maximo()).withBackoff(politica.espera(), politica.fator())
				.thenRemove(this::desistiu);
	}

	/**
	 * {@code tentativa}: falhas anteriores desta instancia (0 na primeira execucao). <b>Precisa ser idempotente</b>: a
	 * mesma instancia pode rodar mais de uma vez (revivida pelo heartbeat ~2 min depois de um no sumir, deploy no meio,
	 * thread que sobrevive ao stop()).
	 */
	protected abstract void executar(P payload, int tentativa) throws Exception;

	/**
	 * Chamado <b>no maximo</b> uma vez quando as retentativas acabam, com a linha ja removida: aqui da para reenfileirar a
	 * mesma instancia (numa transacao propria). Pode nao rodar (no que morre entre remover a linha e chamar o gancho): a
	 * rede de seguranca e a varredura de pedidos presos. {@code erro} pode ser nulo. Excecao aqui so vai para o log.
	 */
	protected void aoDesistir(P payload, Throwable erro) {}

	/** Instancia deterministica {@code <job>/<id>}: o mesmo pedido nunca tem dois jobs do mesmo tipo na fila. */
	public final String instancia(Object id) {
		return getName() + "/" + id;
	}

	@Override
	public final void executeOnce(TaskInstance<P> instancia, ExecutionContext ctx) {
		try {
			executar(instancia.getData(), ctx.getExecution().consecutiveFailures);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** O OneTimeTask guarda o handler no construtor, antes de {@code this} existir: por isso o override. */
	@Override
	public FailureHandler<P> getFailureHandler() {
		return aoFalhar;
	}

	@SuppressWarnings("unchecked")
	private void desistiu(ExecutionComplete c) {
		var instancia = (TaskInstance<P>) c.getExecution().taskInstance;
		var erro = c.getCause().orElse(null);
		try {
			aoDesistir(instancia.getData(), erro);
		} catch (RuntimeException e) {
			LOG.error("Job {} ({}): aoDesistir falhou", getName(), instancia.getId(), e);
		}
		LOG.error("Job {} ({}) desistiu depois das retentativas", getName(), instancia.getId(), erro);
		if (erro != null) Sentry.captureException(erro);
	}
}
