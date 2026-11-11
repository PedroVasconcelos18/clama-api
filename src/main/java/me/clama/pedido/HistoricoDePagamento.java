package me.clama.pedido;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta que o {@code pagamento} implementa para o detalhe do pedido no admin (o pedido nao pode depender do pagamento).
 * Sem o copia-e-cola do Pix, sem payload: so o que o admin precisa para entender o que aconteceu.
 */
public interface HistoricoDePagamento {

	Historico de(UUID pedidoId);

	/** {@code status}: PAGA, CANCELADA, EXPIRADA ou PENDENTE. */
	record CobrancaDoPedido(int n, String status, long mpPagamentoId, Instant criadaEm, Instant expiraEm, Instant canceladaEm,
			Instant pagaEm) {}

	record RepasseDoPedido(Long id, String status, long valorBaseCentavos, int percentual, long valorCentavos,
			Instant criadoEm, Instant transferidoEm, Instant canceladoEm, String motivo) {}

	record EventoDoPedido(Long id, String tipo, String status, String motivo, Instant recebidoEm) {}

	/** Cobrancas da mais nova para a mais antiga; eventos, do mais recente; repasse nulo se nao ha. */
	record Historico(List<CobrancaDoPedido> cobrancas, RepasseDoPedido repasse, List<EventoDoPedido> eventos) {}
}
