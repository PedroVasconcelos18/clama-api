package me.clama.pedido.web;

import java.time.Instant;
import java.util.UUID;
import me.clama.pedido.StatusPedido;

/** Linha da lista do admin. So nome e e-mail sao decifrados; telefone, CPF, pedido e oracao nem sao lidos do banco. */
record PedidoAdminItem(UUID id, StatusPedido status, long valorCentavos, String plano, Instant criadoEm, boolean gratuito,
		String nome, String email) {}
