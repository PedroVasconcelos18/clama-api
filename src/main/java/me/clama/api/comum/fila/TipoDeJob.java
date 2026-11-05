package me.clama.api.comum.fila;

import java.time.Duration;

/** Um tipo de job da fila. Cada etapa declara os seus como bean Spring. Payload: record serializado em JSON. */
public interface TipoDeJob<P> {
	String nome();

	Class<P> payload();

	/** Espera fixa entre tentativas (30 s ou 60 s, como o default_retry_delay das tasks Celery). */
	Duration esperaEntreTentativas();

	/** Retentativas depois da primeira falha (max_retries=3 do Celery). */
	default int maxRetentativas() {
		return 3;
	}

	void executar(P payload) throws Exception;
}
