package me.clama.freemium.web;

import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** O submit e a confirmacao sao anonimos mas com CSRF: o front pega o cookie XSRF antes, como na doacao. */
@Configuration
class SegurancaDoFreemium {

	@Bean
	@Order(34)
	SecurityFilterChain cadeiaDoFreemium(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatchers(m -> m.requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/freemium/pedidos"),
						PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/freemium/confirmacao")))
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.build();
	}
}
