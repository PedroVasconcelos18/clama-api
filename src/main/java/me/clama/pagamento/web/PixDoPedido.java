package me.clama.pagamento.web;

import java.time.Instant;
import java.util.UUID;
import me.clama.pagamento.interno.Cobranca;

/** Pix para pagar o pedido: copia-e-cola, PNG do QR em base64 e quando vence. */
record PixDoPedido(UUID pedidoId, String copiaECola, String qrBase64, Instant expiraEm) {

	static PixDoPedido de(Cobranca c) {
		return new PixDoPedido(c.getPedidoId(), c.getPixCopiaECola(), c.getPixQrBase64(), c.getExpiraEm());
	}
}
