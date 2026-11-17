package me.clama.oracao.web;

import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** A triagem do clamor e anonima mas com CSRF: o front pega o cookie XSRF antes, como no freemium. */
@Configuration
class SegurancaDaTriagem {

	@Bean
	@Order(35)
	SecurityFilterChain cadeiaDaTriagem(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/triagem"))
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.build();
	}
}
