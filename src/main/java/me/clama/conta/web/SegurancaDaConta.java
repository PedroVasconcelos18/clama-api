package me.clama.conta.web;

import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Login, logout, cadastro e senha esquecida sao anonimos (com CSRF: o front faz um GET antes); o resto exige sessao. */
@Configuration
class SegurancaDaConta {

	@Bean
	@Order(10)
	SecurityFilterChain cadeiaDaConta(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatcher("/api/auth/**", "/api/conta/**")
				.authorizeHttpRequests(a -> a
						.requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/esqueci-senha",
								"/api/auth/redefinicao-de-senha", "/api/auth/cadastro").permitAll()
						.anyRequest().authenticated())
				.build();
	}
}
