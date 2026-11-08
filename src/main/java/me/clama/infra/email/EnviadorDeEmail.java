package me.clama.infra.email;

/** Porta de saida de e-mail. Falha (RuntimeException) quando nao conseguiu entregar a Resend. */
public interface EnviadorDeEmail {

	/** A mesma {@code chaveIdempotencia} em 24 h nao reenvia: use uma chave estavel por e-mail logico. */
	void enviar(Email email, String chaveIdempotencia);
}
