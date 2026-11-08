package me.clama.infra.seguranca;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cadeia padrao: nega tudo, exceto health e /api/admin (papel ADMIN). Cada modulo libera as
 * proprias rotas publicas num SecurityFilterChain proprio, com @Order menor e securityMatcher.
 */
@Configuration
public class SegurancaConfig {

	/**
	 * Cadeia que nega por padrao; fica por ultimo para nunca sombrear a cadeia de um modulo.
	 */
	@Bean
	@Order(Ordered.LOWEST_PRECEDENCE)
	SecurityFilterChain cadeiaPadrao(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return aplicarPadrao(http, respostas)
				.authorizeHttpRequests(a -> a
						.requestMatchers("/actuator/health/**").permitAll()
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						.anyRequest().denyAll())
				.build();
	}

	/**
	 * Configuracao comum a toda cadeia (CSRF de SPA, headers, 401/403 em JSON, sem login embutido).
	 * Um modulo declara a cadeia dele assim: {@code aplicarPadrao(http, respostas).securityMatcher(...)
	 * .authorizeHttpRequests(...).build()}, com {@code @Order} menor que o da cadeia padrao.
	 */
	public static HttpSecurity aplicarPadrao(HttpSecurity http, RespostasDeSeguranca respostas) {
		return http
				.csrf(c -> c.spa()) // cookie XSRF-TOKEN (legivel pelo JS) + handler de SPA; header X-XSRF-TOKEN
				.addFilterAfter(new CookieCsrfFilter(), CsrfFilter.class)
				.httpBasic(b -> b.disable())
				.formLogin(f -> f.disable())
				.logout(l -> l.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				.exceptionHandling(e -> e.authenticationEntryPoint(respostas).accessDeniedHandler(respostas))
				.headers(h -> h
						.httpStrictTransportSecurity(s -> s.maxAgeInSeconds(31536000).includeSubDomains(true))
						.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
						.contentTypeOptions(Customizer.withDefaults())
						.referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN))
						.crossOriginOpenerPolicy(c -> c.policy(CrossOriginOpenerPolicy.SAME_ORIGIN)));
	}

	/** O token do Security 7 e carregado sob demanda; ler o valor forca o Set-Cookie ja na primeira resposta. */
	private static final class CookieCsrfFilter extends OncePerRequestFilter {
		@Override
		protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
				throws ServletException, IOException {
			var token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
			if (token != null) token.getToken();
			chain.doFilter(req, res);
		}
	}

	/** Ninguem existe ate a onda 2 (login por sessao); evita o usuario em memoria e a senha gerada do Boot. */
	@Bean
	UserDetailsService usuarios() {
		return username -> {
			throw new UsernameNotFoundException("Login por sessao chega na onda 2");
		};
	}
}
