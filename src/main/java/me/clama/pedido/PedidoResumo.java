package me.clama.pedido;

import java.time.Instant;
import java.util.UUID;

/** Leitura sem PII: o que o pagamento e os relatorios precisam saber do pedido. {@code ultimoErro}: codigo curto ou nulo. */
public record PedidoResumo(UUID id, StatusPedido status, Long planoId, Long instituicaoId, long valorCentavos,
		boolean gratuito, Instant criadoEm, Instant atualizadoEm, String ultimoErro) {}
