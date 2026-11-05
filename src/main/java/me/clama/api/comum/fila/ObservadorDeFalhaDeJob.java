package me.clama.api.comum.fila;

/** Chamado quando um job esgota as retentativas, depois do {@link TipoDeJob#esgotou}. {@code erro} pode ser nulo. */
public interface ObservadorDeFalhaDeJob {
	void falhou(String tipo, String instancia, Throwable erro);
}
