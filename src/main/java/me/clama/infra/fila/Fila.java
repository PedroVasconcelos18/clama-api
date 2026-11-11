package me.clama.infra.fila;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Enfileira na transacao do chamador (o starter envolve o DataSource num TransactionAwareDataSourceProxy): rollback
 * descarta o job, commit o libera. Uma instancia ja na fila (agendada ou em execucao) nao e duplicada.
 */
@Component
public class Fila {

	private final SchedulerClient cliente;
	private final JdbcTemplate jdbc;

	Fila(SchedulerClient cliente, JdbcTemplate jdbc) {
		this.cliente = cliente;
		this.jdbc = jdbc;
	}

	/**
	 * {@code false} se a instancia ja existe (no-op). O {@code scheduleIfNotExists} do db-scheduler 17 e SELECT + INSERT
	 * sem ON CONFLICT: duas transacoes na mesma instancia fariam o INSERT da 2a violar a chave e abortar a transacao do
	 * chamador. A trava consultiva transacional serializa as duas: a 2a espera o commit da 1a, ve a linha e retorna false.
	 */
	public <P> boolean enfileirar(TipoDeJob<P> tipo, String instancia, P payload, Instant quando) {
		exigirTransacao("enfileirar");
		jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, "fila:" + tipo.getName() + ":" + instancia);
		return cliente.scheduleIfNotExists(tipo.instance(instancia, payload), quando);
	}

	/** Agendada, em retentativa ou em execucao: a linha so some quando o job termina ou desiste. */
	public boolean existePendente(String tarefa, String instancia) {
		return cliente.getScheduledExecution(TaskInstanceId.of(tarefa, instancia)).isPresent();
	}

	/** Traz para agora uma instancia agendada. {@code false} se ela nao existe ou ja esta em execucao. */
	public boolean antecipar(String tarefa, String instancia) {
		exigirTransacao("antecipar");
		try {
			return cliente.reschedule(TaskInstanceId.of(tarefa, instancia), Instant.now());
		} catch (TaskInstanceNotFoundException | TaskInstanceCurrentlyExecutingException e) {
			return false;
		}
	}

	private static void exigirTransacao(String metodo) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Fila." + metodo + " exige transacao ativa");
		}
	}
}
