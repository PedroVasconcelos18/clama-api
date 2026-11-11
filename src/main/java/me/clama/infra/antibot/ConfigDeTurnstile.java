package me.clama.infra.antibot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
class ConfigDeTurnstile {

	private static final Logger LOG = LoggerFactory.getLogger(ConfigDeTurnstile.class);

	@Bean
	Turnstile turnstile(@Value("${clama.turnstile.url}") String url, @Value("${clama.turnstile.secret:}") String secret, Environment env) {
		var teste = env.acceptsProfiles(Profiles.of("local", "test"));
		if (!teste && (secret == null || secret.isBlank())) {
			LOG.error("TURNSTILE_SECRET_KEY ausente: toda verificacao anti-robo vai falhar e as doacoes anonimas ficam bloqueadas ate configurar");
		}
		return new Turnstile(url, secret, teste);
	}
}
