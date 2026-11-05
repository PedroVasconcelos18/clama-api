package me.clama.api.comum.fila;

import com.github.kagkarlsson.scheduler.SchedulerClient;
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
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Fila.enfileirar exige transacao ativa: " + tipo.nome());
		}
		@SuppressWarnings("unchecked")
		OneTimeTask<P> tarefa = (OneTimeTask<P>) tarefas.porNome().get(tipo.nome());
		if (tarefa == null) {
			throw new IllegalStateException("TipoDeJob nao registrado como bean: " + tipo.nome());
		}
		client.schedule(tarefa.instance(UUID.randomUUID().toString(), payload), quando);
	}
}
