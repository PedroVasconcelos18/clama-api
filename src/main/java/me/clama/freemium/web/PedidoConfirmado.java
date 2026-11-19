package me.clama.freemium.web;

import java.util.UUID;
import me.clama.pedido.StatusPedido;

/**
 * 200 da confirmacao: o pedido segue para a geracao da oracao (PAGO). {@code tokenDeSenha}: so quando a conta ainda nao
 * tem senha, para a tela defini-la pelo {@code /api/auth/redefinicao-de-senha}; senao nulo. E uma credencial.
 */
public record PedidoConfirmado(UUID id, StatusPedido status, String tokenDeSenha) {}
