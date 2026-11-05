package me.clama.api.comum.observabilidade;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro mais externo: excecao que escapa da cadeia (ex.: banco fora no FiltroJwtCookie) vira 500 sem passar pelo
 * MVC, entao o Sentry nao veria. A deduplicacao do SDK (mesma excecao ou causa ja capturada) evita repetir as que o
 * SentryExceptionResolver ja reportou. No-op com o Sentry desligado.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CapturaDeFiltro extends OncePerRequestFilter {
	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
			throws ServletException, IOException {
		try {
			chain.doFilter(req, resp);
		} catch (ServletException | IOException | RuntimeException | Error e) {
			SentryConfig.capturar("origem", "filtro", e);
			throw e;
		}
	}
}
