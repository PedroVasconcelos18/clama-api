package me.clama.freemium.web;

import java.util.UUID;
import me.clama.pedido.StatusPedido;

/** 200 da confirmacao: o pedido segue para a geracao da oracao (PAGO). */
public record PedidoConfirmado(UUID id, StatusPedido status) {}
