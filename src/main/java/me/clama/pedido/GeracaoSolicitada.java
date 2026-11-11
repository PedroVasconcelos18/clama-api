package me.clama.pedido;

import java.util.UUID;

/** O pedido esta pronto para gerar a oracao (pago, gratuito, regenerar). Publicado dentro da transacao; o {@code oracao} enfileira. */
public record GeracaoSolicitada(UUID pedidoId) {}
