package me.clama.pedido.web;

import java.time.Instant;
import java.util.UUID;
import me.clama.pedido.StatusPedido;

/**
 * Status por uuid, sem login: nada de dados pessoais do doador. {@code oracaoGerada} so em ENVIADA;
 * {@code mensagemPastoral} so no erro por falta de creditos da IA (a oracao sai em ate 24 h).
 */
public record StatusPublico(UUID id, StatusPedido status, String plano, Long instituicao, long valorCentavos,
		Instant criadoEm, String mensagemPastoral, String oracaoGerada) {}
