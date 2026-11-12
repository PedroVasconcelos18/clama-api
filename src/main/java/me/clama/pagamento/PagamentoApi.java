package me.clama.pagamento;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Interface do modulo para os outros (a gratuidade do admin e a LGPD). */
public interface PagamentoApi {

	/**
	 * Cancela no Mercado Pago o Pix pendente do pedido e a marca como cancelada ({@code canceladaEm}; a linha fica); sem cobranca, nada. Trava o pedido: chamar na
	 * mesma transacao de {@code PedidoApi.concederGratuidade}. Pagamento ja aprovado: 409 (o webhook ou o reprocessamento
	 * confirmam o pedido; a gratuidade nao pode passar por cima de dinheiro recebido).
	 */
	void cancelarCobranca(UUID pedidoId);

	/** O pagamento que confirmou o pedido: quando e o id no Mercado Pago. */
	record Pagamento(Instant confirmadoEm, long mpPagamentoId) {}

	/** Dos pedidos dados, os que tem qualquer cobranca (paga ou nao: o valor_divergente recebe sem marcar paga_em), evento aprovado processado ou repasse: registro contabil que a exclusao LGPD anonimiza em vez de apagar. */
	Set<UUID> pedidosComPagamento(Collection<UUID> pedidoIds);

	/** O pagamento confirmado de cada pedido que tem um (para a exportacao LGPD); os demais ficam fora do mapa. */
	Map<UUID, Pagamento> pagamentos(Collection<UUID> pedidoIds);
}
