package me.clama.infra.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code clama.email.*}: sem chave a aplicacao sobe e o envio real falha com mensagem clara. */
@ConfigurationProperties("clama.email")
public record EmailProps(@DefaultValue("") String resendApiKey,
		@DefaultValue("Clama <nao-responda@clama.me>") String remetente) {}
