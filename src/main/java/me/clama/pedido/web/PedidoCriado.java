package me.clama.pedido.web;

import java.time.Instant;
import java.util.UUID;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.interno.Pedido;

/** 201 de criar pedido e doacao. Sem Pix: ele vem de {@code POST /api/pedidos/{id}/pix}. */
public record PedidoCriado(UUID id, StatusPedido status, Long instituicao, long valorCentavos, Instant criadoEm) {

	static PedidoCriado de(Pedido p) {
		return new PedidoCriado(p.getId(), p.getStatus(), p.getInstituicaoId(), p.getValorCentavos(), p.getCriadoEm());
	}
}
