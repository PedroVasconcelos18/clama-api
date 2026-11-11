package me.clama.pedido;

import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;

public class PedidoNaoEncontradoException extends ErroDeNegocio {

	public PedidoNaoEncontradoException() {
		super(HttpStatus.NOT_FOUND, "pedido_nao_encontrado", "Pedido nao encontrado.", "Não encontramos esse pedido.");
	}
}
