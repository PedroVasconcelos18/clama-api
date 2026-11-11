package me.clama.pagamento.web;

import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * {@code POST /api/pedidos/{id}/pix}: anonimo (a doacao e anonima; o uuid e o segredo), com CSRF como a criacao do pedido.
 * O front ja tem o cookie XSRF do POST que criou o pedido.
 */
@Configuration
class SegurancaDoPagamento {

	@Bean
	@Order(32)
	SecurityFilterChain cadeiaDoPix(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/pedidos/*/pix"))
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.build();
	}
}
