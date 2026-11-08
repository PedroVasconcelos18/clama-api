package me.clama.conta;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code clama.conta.*}: {@code frontendUrl} e a base dos links dos e-mails (sem barra final). */
@ConfigurationProperties("clama.conta")
record ContaProps(@DefaultValue("http://localhost:5173") String frontendUrl) {}
