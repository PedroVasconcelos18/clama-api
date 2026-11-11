package me.clama.pedido;

import java.util.UUID;

/** O admin pediu para reenviar a oracao ja gerada. Publicado dentro da transacao. */
public record ReenvioSolicitado(UUID pedidoId) {}
