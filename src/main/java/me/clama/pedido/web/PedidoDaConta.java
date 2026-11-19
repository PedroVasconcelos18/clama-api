package me.clama.pedido.web;

import java.time.Instant;
import java.util.UUID;
import me.clama.pedido.StatusPedido;

/** Item de "meus pedidos". {@code oracaoGerada} so em ENVIADA. */
public record PedidoDaConta(UUID id, StatusPedido status, String plano, long valorCentavos, boolean gratuito,
		Instant criadoEm, String oracaoGerada, String tema) {}
