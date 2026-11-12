package me.clama.infra.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * INV:26. Fora de local/teste, segredo de integracao em branco para o boot (como as chaves de cripto e o segredo do
 * webhook do MP): uma variavel esquecida nao pode subir verde e falhar na primeira doacao.
 */
@Component
class ConfiguracaoObrigatoria {

	/** propriedade -> variavel de ambiente que o operador precisa definir. */
	static final Map<String, String> OBRIGATORIAS = Map.of(
			"clama.pagamento.mercadopago-token", "MERCADOPAGO_ACCESS_TOKEN",
			"clama.oracao.anthropic-api-key", "ANTHROPIC_API_KEY",
			"clama.turnstile.secret", "TURNSTILE_SECRET_KEY",
			"clama.email.resend-api-key", "RESEND_API_KEY");

	ConfiguracaoObrigatoria(Environment env) {
		conferir(env);
	}

	static void conferir(Environment env) {
		if (env.acceptsProfiles(Profiles.of("local", "test"))) return;
		List<String> faltando = new ArrayList<>();
		OBRIGATORIAS.forEach((propriedade, variavel) -> {
			if (env.getProperty(propriedade, "").isBlank()) faltando.add(variavel);
		});
		if (!faltando.isEmpty()) {
			throw new IllegalStateException("Configuracao obrigatoria ausente: " + String.join(", ", faltando.stream().sorted().toList()));
		}
	}
}
