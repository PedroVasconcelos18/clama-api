package me.clama.api.comum.agenda;

import java.util.Map;

public interface TarefaAgendada {
	/** Chave em clama.agenda.&lt;nome&gt;.cron / .modo. */
	String nome();

	/** Tarefa que altera dados comeca em LISTAR ate alguem ligar o modo. */
	boolean alteraDados();

	ResultadoDaTarefa executar(ModoDeExecucao modo);

	/** resumo = o mesmo dict que a task do Django devolve. */
	record ResultadoDaTarefa(String nome, ModoDeExecucao modo, Map<String, Object> resumo) {
	}
}
