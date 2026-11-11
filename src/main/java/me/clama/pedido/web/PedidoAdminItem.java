package me.clama.pedido.web;

import java.time.Instant;
import java.util.UUID;
import me.clama.pedido.StatusPedido;

/** Linha da lista do admin. So o nome do doador e decifrado; e-mail, telefone e CPF ficam para o detalhe (o CPF nem la). */
record PedidoAdminItem(UUID id, StatusPedido status, long valorCentavos, String plano, Instant criadoEm, boolean gratuito,
		String nome) {}
