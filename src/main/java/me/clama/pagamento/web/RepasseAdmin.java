package me.clama.pagamento.web;

import java.time.Instant;
import java.util.UUID;
import me.clama.pagamento.interno.AdminDePagamento.Repasse;
import org.springframework.lang.Nullable;

/** Repasse no admin. Sem PII: o pedido so por id, a instituicao pelo nome do catalogo. */
record RepasseAdmin(Long id, UUID pedidoId, Long instituicaoId, @Nullable String instituicao, long valorBaseCentavos,
		int percentual, long valorCentavos, String status, Instant criadoEm, @Nullable Instant transferidoEm,
		@Nullable Instant canceladoEm, @Nullable String motivo) {

	static RepasseAdmin de(Repasse r, @Nullable String instituicao) {
		return new RepasseAdmin(r.id(), r.pedidoId(), r.instituicaoId(), instituicao, r.valorBaseCentavos(), r.percentual(),
				r.valorCentavos(), r.status(), r.criadoEm(), r.transferidoEm(), r.canceladoEm(), r.motivo());
	}
}
