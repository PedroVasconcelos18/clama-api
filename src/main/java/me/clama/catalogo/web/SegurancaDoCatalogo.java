package me.clama.catalogo.web;

import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Vitrine publica, so GET e HEAD. Sem CSRF e sem sessao: nao ha escrita aqui, e um Set-Cookie (XSRF-TOKEN/SESSION) numa
 * resposta cacheavel poderia ser guardado por um cache publico e servido a outro usuario.
 */
@Configuration
class SegurancaDoCatalogo {

	@Bean
	@Order(20)
	SecurityFilterChain cadeiaDoCatalogo(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatcher("/api/planos", "/api/instituicoes", "/api/instituicoes/*/logo")
				.csrf(c -> c.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/**").permitAll().requestMatchers(HttpMethod.HEAD, "/**").permitAll().anyRequest().denyAll())
				.build();
	}
}
