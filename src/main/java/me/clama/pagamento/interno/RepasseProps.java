package me.clama.pagamento.interno;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code clama.repasse.*}: o percentual vira fotografia em cada repasse registrado (mudar aqui nao reescreve o passado). */
@ConfigurationProperties("clama.repasse")
record RepasseProps(@DefaultValue("20") int percentual) {

	RepasseProps {
		if (percentual < 0 || percentual > 100) {
			throw new IllegalArgumentException("clama.repasse.percentual precisa estar entre 0 e 100: " + percentual);
		}
	}
}
