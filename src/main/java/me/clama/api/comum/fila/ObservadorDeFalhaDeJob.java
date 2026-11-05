package me.clama.api.comum.fila;

/** Chamado quando um job esgota as retentativas. A Task 5 pluga o Sentry aqui. */
public interface ObservadorDeFalhaDeJob {
	void falhou(String tipo, Throwable erro);
}
