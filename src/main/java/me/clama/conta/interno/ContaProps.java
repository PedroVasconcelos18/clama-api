package me.clama.conta.interno;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code clama.conta.*}: {@code frontendUrl} e a base dos links dos e-mails (sem barra final); {@code adminResgateEmail}
 * (CLAMA_ADMIN_RESGATE_EMAIL) e a conta que o boot promove a superusuario, vazio = nada.
 */
@ConfigurationProperties("clama.conta")
public record ContaProps(@DefaultValue("http://localhost:5173") String frontendUrl,
		@DefaultValue("") String adminResgateEmail) {

	/** {@code <frontendUrl>/<rota>#token=<token>}: no fragmento, o token nao vai ao servidor nem ao Referer. */
	public String linkDeSenha(String rota, String token) {
		return frontendUrl.replaceAll("/+$", "") + "/" + rota + "#token=" + token;
	}
}
