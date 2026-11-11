package me.clama.blog.web.widget;

import jakarta.servlet.http.HttpServletRequest;
import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Duas cadeias do widget (mapa §4.3). A de leitura e igual a do catalogo (sem sessao e sem CSRF: um Set-Cookie numa
 * resposta cacheavel vazaria para outro leitor); a de escrita e a padrao, mas le o token no header {@code X-CSRFToken}
 * (o do Django, que o widget manda), comparado em texto simples com o cookie. O resto da API continua com o
 * {@code X-XSRF-TOKEN} do SPA. Ordens 40 e 41: depois de 10 a 34 e antes da cadeia que nega tudo.
 */
@Configuration
class SegurancaDoWidget {

	/** Leitura publica; e as rotas sem a barra final, que devem chegar ao MVC e dar 404 (nao 401). */
	@Bean
	@Order(40)
	SecurityFilterChain cadeiaDeLeituraDoWidget(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		var leitura = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/blog/posts/*/comments/");
		var semBarra = PathPatternRequestMatcher.withDefaults();
		var semBarraComentarios = semBarra.matcher(HttpMethod.GET, "/api/blog/posts/*/comments");
		var semBarraCurtida = semBarra.matcher(HttpMethod.GET, "/api/blog/posts/*/like");
		var semBarraCsrf = semBarra.matcher(HttpMethod.GET, "/api/csrf");
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatchers(m -> m.requestMatchers(leitura, semBarraComentarios, semBarraCurtida, semBarraCsrf))
				.csrf(c -> c.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.build();
	}

	@Bean
	@Order(41)
	SecurityFilterChain cadeiaDeEscritaDoWidget(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		var rotas = PathPatternRequestMatcher.withDefaults();
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatchers(m -> m.requestMatchers(
						rotas.matcher(HttpMethod.GET, "/api/csrf/"),
						rotas.matcher(HttpMethod.POST, "/api/blog/posts/*/comments/"),
						rotas.matcher(HttpMethod.POST, "/api/blog/posts/*/like/")))
				.csrf(c -> c.csrfTokenRequestHandler(new TokenDoDjango()))
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.GET, "/api/csrf/").permitAll()
						.anyRequest().authenticated())
				.build();
	}

	/** Token em texto simples (o cookie guarda o valor cru), lido de {@code X-CSRFToken}; so esse header vale aqui. */
	static final class TokenDoDjango extends CsrfTokenRequestAttributeHandler {
		@Override
		public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
			return request.getHeader("X-CSRFToken");
		}
	}
}
