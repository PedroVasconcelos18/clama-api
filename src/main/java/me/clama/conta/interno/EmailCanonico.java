package me.clama.conta.interno;

import me.clama.infra.cripto.HmacDeIdentificador;

/** Forma comparavel do e-mail (regra unica em infra: o HMAC do pedido usa a mesma). */
public final class EmailCanonico {
	private EmailCanonico() {}

	public static String de(String email) {
		return HmacDeIdentificador.normalizarEmail(email);
	}
}
