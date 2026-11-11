package me.clama.pedido;

import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;

/** Mudanca de status que a maquina de estados nao permite: 409. */
public class TransicaoInvalidaException extends ErroDeNegocio {

	public TransicaoInvalidaException(StatusPedido de, String acao) {
		super(HttpStatus.CONFLICT, "transicao_invalida", "Pedido em " + de + " nao aceita " + acao + ".",
				"Esse pedido não está em um estado que permita isso agora.");
	}
}
