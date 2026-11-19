package me.clama.pagamento.web;

import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Duas cadeias. {@code POST /api/pedidos/{id}/pix}: anonimo (a doacao e anonima; o uuid e o segredo), com CSRF como a criacao do pedido.
 * O front ja tem o cookie XSRF do POST que criou o pedido. O webhook do Mercado Pago e anonimo, sem CSRF e sem sessao: a
 * autenticacao e a assinatura HMAC, conferida por um filtro so desta cadeia.
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

	/** Metas das instituicoes: leitura anonima e cacheavel, so GET, sem sessao (como o catalogo). */
	@Bean
	@Order(36)
	SecurityFilterChain cadeiaDeMetas(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatcher("/api/metas")
				.csrf(c -> c.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/**").permitAll().anyRequest().denyAll())
				.build();
	}

	@Bean
	@Order(33)
	SecurityFilterChain cadeiaDoWebhook(HttpSecurity http, RespostasDeSeguranca respostas, Environment env,
			@Value("${clama.pagamento.webhook-segredo:}") String segredo) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatchers(m -> m.requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, WebhookMercadoPagoController.CAMINHO),
						PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, WebhookMercadoPagoController.CAMINHO + "/")))
				.csrf(c -> c.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(new AssinaturaMercadoPago(AssinaturaMercadoPago.exigirSegredo(segredo, env)), AuthorizationFilter.class)
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.build();
	}
}
