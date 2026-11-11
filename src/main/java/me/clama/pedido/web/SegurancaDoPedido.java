package me.clama.pedido.web;

import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Duas cadeias. O status publico ({@code GET /api/pedidos/{id}}) e so leitura: sem sessao e sem CSRF, como a vitrine
 * (o uuid e o segredo, e um Set-Cookie numa resposta cacheavel vazaria para outro usuario). A criacao
 * ({@code POST /api/pedidos}, {@code /gratuito}, {@code /api/doacoes}) segue o fluxo padrao com CSRF: a doacao e anonima
 * mas o front pega o cookie XSRF antes, como no login. "Meus pedidos" ({@code /api/conta/pedidos}) cai na cadeia da conta.
 */
@Configuration
class SegurancaDoPedido {

	@Bean
	@Order(30)
	SecurityFilterChain cadeiaDoStatusPublico(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/pedidos/*"))
				.csrf(c -> c.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.build();
	}

	@Bean
	@Order(31)
	SecurityFilterChain cadeiaDaCriacao(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatcher("/api/pedidos", "/api/pedidos/gratuito", "/api/doacoes")
				.authorizeHttpRequests(a -> a
						.requestMatchers("/api/doacoes").permitAll()
						.anyRequest().authenticated())
				.build();
	}
}
