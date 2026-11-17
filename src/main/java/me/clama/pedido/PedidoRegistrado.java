package me.clama.pedido;

import java.util.UUID;

/** Pedido criado (qualquer das 4 rotas). Publicado dentro da transacao da criacao; o {@code oracao} enfileira a triagem. */
public record PedidoRegistrado(UUID pedidoId) {}
