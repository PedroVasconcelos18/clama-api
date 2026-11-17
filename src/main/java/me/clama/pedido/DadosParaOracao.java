package me.clama.pedido;

import java.util.UUID;

/** O que a geracao e o envio da oracao leem do pedido (tem PII em claro: nao logar). {@code oracao} e nula antes de gerada. */
public record DadosParaOracao(UUID id, StatusPedido status, Long planoId, boolean gratuito, String nome, String email,
		Short idade, String sexo, String pedidoOracao, Long usuarioId, String tema, String oracao, int tentativas, String ultimoErro) {

	/** Sem PII no log. */
	@Override
	public String toString() {
		return "DadosParaOracao[id=" + id + "]";
	}
}
