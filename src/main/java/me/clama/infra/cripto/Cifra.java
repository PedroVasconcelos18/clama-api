package me.clama.infra.cripto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM. Valor gravado: {@code v1:} + base64(nonce(12) ‖ texto cifrado ‖ tag(16)). O prefixo de versao
 * permite trocar de chave ou de algoritmo no futuro sem ambiguidade.
 */
public final class Cifra {

	private static final String PREFIXO = "v1:";
	private static final int NONCE = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom ALEATORIO = new SecureRandom();

	private final SecretKeySpec chave;

	public Cifra(String chaveBase64) {
		this.chave = new SecretKeySpec(ChaveBase64.de("clama.cripto.chave", chaveBase64), "AES");
	}

	public String cifrar(String claro) {
		try {
			var nonce = new byte[NONCE];
			ALEATORIO.nextBytes(nonce);
			var c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(TAG_BITS, nonce));
			var cifrado = c.doFinal(claro.getBytes(StandardCharsets.UTF_8));
			return PREFIXO + Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE + cifrado.length).put(nonce).put(cifrado).array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("falha ao cifrar", e);
		}
	}

	/** Falha (IllegalStateException) se o valor foi adulterado, veio de outra chave ou nao esta no formato v1. */
	public String decifrar(String gravado) {
		try {
			if (!gravado.startsWith(PREFIXO)) throw new IllegalStateException("formato de cifra desconhecido");
			var bytes = Base64.getDecoder().decode(gravado.substring(PREFIXO.length()));
			if (bytes.length < NONCE + TAG_BITS / 8) throw new IllegalStateException("valor cifrado curto demais");
			var c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE));
			return new String(c.doFinal(bytes, NONCE, bytes.length - NONCE), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("falha ao decifrar", e);
		}
	}
}
