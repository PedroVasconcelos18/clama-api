package me.clama.infra.cripto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 (hex) de identificadores, para buscar por igualdade sem decifrar. A normalizacao vive aqui porque a
 * {@code infra} nao pode depender de {@code conta}; o {@code EmailCanonico} da conta delega para {@link #normalizarEmail}.
 */
public final class HmacDeIdentificador {

	private final SecretKeySpec chave;

	public HmacDeIdentificador(String chaveBase64) {
		this.chave = new SecretKeySpec(ChaveBase64.de("clama.cripto.chave-hmac", chaveBase64), "HmacSHA256");
	}

	public String email(String email) {
		return hash(normalizarEmail(email));
	}

	public String cpf(String cpfOuCnpj) {
		return hash(soDigitos(cpfOuCnpj));
	}

	public String telefone(String telefone) {
		return hash(soDigitos(telefone));
	}

	/** HMAC do valor ja normalizado. */
	String hash(String normalizado) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(chave);
			return HexFormat.of().formatHex(mac.doFinal(normalizado.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Minusculas e, no Gmail, sem pontos e sem "+tag" (googlemail vira gmail). */
	public static String normalizarEmail(String email) {
		if (email == null) return "";
		String e = email.strip().toLowerCase(Locale.ROOT);
		int at = e.indexOf('@');
		if (at < 0) return e;
		String local = e.substring(0, at);
		String dominio = e.substring(at + 1);
		if (!dominio.equals("gmail.com") && !dominio.equals("googlemail.com")) return e;
		int mais = local.indexOf('+');
		if (mais >= 0) local = local.substring(0, mais);
		return local.replace(".", "") + "@gmail.com";
	}

	private static String soDigitos(String v) {
		return v == null ? "" : v.replaceAll("\\D", "");
	}
}
