package me.clama.pedido;

import java.util.UUID;

/** Dados para criar a cobranca Pix. {@code cpfCnpj} e nulo se o pedido nao o informou. */
public record PedidoCobravel(UUID id, StatusPedido status, long valorCentavos, String nome, String email, String cpfCnpj) {

	/** Sem PII no log. */
	@Override
	public String toString() {
		return "PedidoCobravel[id=" + id + "]";
	}
}
