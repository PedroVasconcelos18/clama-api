package me.clama.api.comum.fila;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Enfileira jobs na mesma transacao do chamador: rollback descarta o job, commit o libera. */
@Component
public class Fila {
	private final SchedulerClient client;
	private final TarefasDaFila tarefas;
	private final JdbcTemplate jdbc;

	Fila(SchedulerClient client, TarefasDaFila tarefas, JdbcTemplate jdbc) {
		this.client = client;
		this.tarefas = tarefas;
		this.jdbc = jdbc;
	}

	public <P> void enfileirar(TipoDeJob<P> tipo, P payload) {
		enfileirar(tipo, payload, Instant.now());
	}

	public <P> void enfileirar(TipoDeJob<P> tipo, P payload, Instant quando) {
		client.schedule(instancia(tipo, UUID.randomUUID().toString(), payload), quando);
	}

	/**
	 * Instancia deterministica: ja existente na fila = no-op, retorna false. "Existente" inclui o job EM EXECUCAO: a linha
	 * so some quando o OneTimeTask termina. Para reagendar a mesma instancia depois de esgotar as retentativas, chame
	 * de dentro de {@link TipoDeJob#esgotou} (a linha ja foi removida), numa transacao propria (TransactionTemplate);
	 * nunca de dentro de {@code executar}.
	 */
	public <P> boolean enfileirar(TipoDeJob<P> tipo, String instancia, P payload) {
		return enfileirar(tipo, instancia, payload, Instant.now());
	}

	/**
	 * O {@code scheduleIfNotExists} do db-scheduler 17.0.0 e SELECT + INSERT simples (sem ON CONFLICT): duas transacoes
	 * concorrentes na mesma instancia fariam o INSERT do 2o violar a unique key e abortar a transacao do chamador.
	 * O advisory lock transacional (mesma conexao, liberado no commit/rollback) serializa as duas: o 2o espera o commit
	 * do 1o, ve a linha no SELECT e retorna false sem erro.
	 */
	public <P> boolean enfileirar(TipoDeJob<P> tipo, String instancia, P payload, Instant quando) {
		var ti = instancia(tipo, instancia, payload);
		jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, "fila:" + tipo.nome() + ":" + instancia);
		return client.scheduleIfNotExists(ti, quando);
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
