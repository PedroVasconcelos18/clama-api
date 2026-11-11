package me.clama.pagamento;

import java.util.UUID;

/** Interface do modulo para os outros (hoje so a gratuidade do admin, onda 4b). */
public interface PagamentoApi {

	/**
	 * Cancela no Mercado Pago o Pix pendente do pedido e apaga a cobranca; sem cobranca, nada. Trava o pedido: chamar na
	 * mesma transacao de {@code PedidoApi.concederGratuidade}. Pagamento ja aprovado: 409 (o webhook ou o reprocessamento
	 * confirmam o pedido; a gratuidade nao pode passar por cima de dinheiro recebido).
	 */
	void cancelarCobranca(UUID pedidoId);
}
