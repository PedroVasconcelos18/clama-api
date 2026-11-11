package me.clama.infra.antibot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
class ConfigDeTurnstile {

	@Bean
	Turnstile turnstile(@Value("${clama.turnstile.url}") String url, @Value("${clama.turnstile.secret:}") String secret, Environment env) {
		return new Turnstile(url, secret, env.acceptsProfiles(Profiles.of("local", "test")));
	}
}
