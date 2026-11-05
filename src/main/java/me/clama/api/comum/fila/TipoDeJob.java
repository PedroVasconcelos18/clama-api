package me.clama.api.comum.fila;

import java.time.Duration;

/**
 * Um tipo de job da fila. Cada etapa declara os seus como bean Spring. Payload: record serializado em JSON.
 *
 * <p><b>Payload so leva IDs</b>: nada de PII, senha ou texto de oracao. Ele fica em texto puro na coluna
 * {@code task_data} de {@code scheduled_tasks}; o job busca o resto no banco.
 *
 * <p>Implemente {@link #executar(Object)} ou, se precisar do numero da tentativa, {@link #executar(Object, int)}.
 */
public interface TipoDeJob<P> {
	String nome();

	Class<P> payload();

	/** Espera antes da 1a retentativa (30 s ou 60 s, como o default_retry_delay das tasks Celery). */
	Duration esperaEntreTentativas();

	/**
	 * Multiplicador da espera a cada falha: espera * fator^retentativa. 1.0 = espera fixa (default_retry_delay);
	 * 2.0 com 30 s = {@code countdown = 30 * 2**self.request.retries} (30, 60, 120 s).
	 */
	default double fatorDeBackoff() {
		return 1.0;
	}

	/** Retentativas depois da primeira falha (max_retries=3 do Celery). */
	default int maxRetentativas() {
		return 3;
	}

	/** {@code retentativa}: falhas anteriores desta instancia, 0 na primeira execucao (= self.request.retries). */
	default void executar(P payload, int retentativa) throws Exception {
		executar(payload);
	}

	default void executar(P payload) throws Exception {
		throw new UnsupportedOperationException("TipoDeJob " + nome() + " nao implementa executar(P) nem executar(P, int)");
	}

	/**
	 * Chamado uma vez quando o job desiste (retentativas esgotadas), antes do observador. {@code erro} pode ser nulo.
	 * Uma excecao aqui e logada e engolida: nao impede o observador nem faz o job rodar de novo.
	 */
	default void esgotou(P payload, Throwable erro) {}
}
