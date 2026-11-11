package me.clama.pagamento.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import me.clama.pagamento.interno.AdminDePagamento.Evento;
import org.springframework.lang.Nullable;

/** Evento do Mercado Pago no admin. O payload (so id, type, action e data.id) vai apenas no detalhe. */
record EventoMpAdmin(Long id, String idExterno, @Nullable String tipo, @Nullable String mpPagamentoId, String status,
		@Nullable String motivo, @Nullable UUID pedidoId, Instant recebidoEm, Instant atualizadoEm,
		@Nullable Map<String, Object> payload) {

	static EventoMpAdmin de(Evento e) {
		return new EventoMpAdmin(e.id(), e.idExterno(), e.tipo(), e.mpPagamentoId(), e.status(), e.motivo(), e.pedidoId(),
				e.recebidoEm(), e.atualizadoEm(), e.payload());
	}
}
