package me.clama.infra.fila;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code rodar-tarefa/<nome>} (D9): o "Rodar agora" do admin. Fora do request, porque uma tarefa longa estouraria o timeout do
 * proxy. Sem retentativa (a execucao ja registra a falha) e uma por tarefa na fila: o clique duplo cai no 409. Payload so com
 * o nome, o modo e o id do autor.
 */
@Component
class JobRodarTarefa extends TipoDeJob<JobRodarTarefa.Disparo> {

	record Disparo(String nome, boolean simulacao, Long autorId) {}

	private static final Logger LOG = LoggerFactory.getLogger(JobRodarTarefa.class);

	private final ObjectProvider<TarefaRecorrente> tarefas;

	JobRodarTarefa(ObjectProvider<TarefaRecorrente> tarefas) {
		super("rodar-tarefa", Disparo.class, Retentativas.NENHUMA);
		this.tarefas = tarefas;
	}

	@Override
	protected void executar(Disparo d, int tentativa) {
		tarefas.stream().filter(t -> t.getName().equals(d.nome())).findFirst().ifPresentOrElse(
				t -> t.rodar(d.simulacao(), d.autorId()), () -> LOG.warn("rodar-tarefa: a tarefa {} nao existe mais", d.nome()));
	}
}
