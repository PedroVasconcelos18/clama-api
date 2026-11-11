package me.clama.infra.cripto;

import java.util.Base64;

final class ChaveBase64 {
	private ChaveBase64() {}

	/** 32 bytes em base64; qualquer outra coisa derruba o boot (nunca ha chave padrao fora dos perfis de dev e teste). */
	static byte[] de(String propriedade, String base64) {
		if (base64 == null || base64.isBlank()) throw new IllegalStateException(propriedade + " ausente: defina a variavel de ambiente (32 bytes em base64)");
		byte[] b;
		try {
			b = Base64.getDecoder().decode(base64.strip());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(propriedade + " nao e base64 valido");
		}
		if (b.length != 32) throw new IllegalStateException(propriedade + " deve ter 32 bytes (tem " + b.length + ")");
		return b;
	}
}
