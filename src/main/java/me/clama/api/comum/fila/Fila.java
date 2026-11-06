package me.clama.api.comum.fila;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Enfileira jobs na mesma transacao do chamador: rollback descarta o job, commit o libera. */
@Component
public class Fila {
	private final SchedulerClient client;
	private final TarefasDaFila tarefas;

	Fila(SchedulerClient client, TarefasDaFila tarefas) {
		this.client = client;
		this.tarefas = tarefas;
	}

	public <P> void enfileirar(TipoDeJob<P> tipo, P payload) {
		enfileirar(tipo, payload, Instant.now());
	}

	public <P> void enfileirar(TipoDeJob<P> tipo, P payload, Instant quando) {
		client.schedule(instancia(tipo, UUID.randomUUID().toString(), payload), quando);
	}

	/** Instancia deterministica: ja existente na fila (agendada ou em execucao) = no-op, retorna false. */
	public <P> boolean enfileirar(TipoDeJob<P> tipo, String instancia, P payload) {
		return enfileirar(tipo, instancia, payload, Instant.now());
	}

	/** {@code scheduleIfNotExists} do db-scheduler e um INSERT ... ON CONFLICT DO NOTHING: nao aborta a transacao do chamador. */
	public <P> boolean enfileirar(TipoDeJob<P> tipo, String instancia, P payload, Instant quando) {
		return client.scheduleIfNotExists(instancia(tipo, instancia, payload), quando);
	}

	private <P> TaskInstance<P> instancia(TipoDeJob<P> tipo, String id, P payload) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Fila.enfileirar exige transacao ativa: " + tipo.nome());
		}
		@SuppressWarnings("unchecked")
		OneTimeTask<P> tarefa = (OneTimeTask<P>) tarefas.porNome().get(tipo.nome());
		if (tarefa == null) {
			throw new IllegalStateException("TipoDeJob nao registrado como bean: " + tipo.nome());
		}
		return tarefa.instance(id, payload);
	}
}
