package me.clama.conta;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code clama.conta.*}: {@code frontendUrl} e a base dos links dos e-mails (sem barra final); {@code adminResgateEmail}
 * (CLAMA_ADMIN_RESGATE_EMAIL) e a conta que o boot promove a superusuario, vazio = nada.
 */
@ConfigurationProperties("clama.conta")
record ContaProps(@DefaultValue("http://localhost:5173") String frontendUrl,
		@DefaultValue("") String adminResgateEmail) {}
