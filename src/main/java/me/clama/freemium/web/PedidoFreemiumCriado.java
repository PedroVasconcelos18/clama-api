package me.clama.freemium.web;

import java.util.UUID;
import me.clama.pedido.StatusPedido;

/** 201 do submit: o pedido nasce aguardando a confirmacao por e-mail. */
public record PedidoFreemiumCriado(UUID id, StatusPedido status) {}
