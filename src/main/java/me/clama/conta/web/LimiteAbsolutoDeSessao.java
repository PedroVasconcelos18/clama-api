package me.clama.conta.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import me.clama.infra.seguranca.RespostasDeSeguranca;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite absoluto de 30 dias a partir do login (D15): a sessao desliza 7 dias, entao um cookie usado toda semana nunca
 * expiraria. Sessao mais velha e invalidada (sai do banco) e a requisicao segue anonima; a rota protegida responde 401
 * com "Sua sessão expirou" ({@link RespostasDeSeguranca}). Roda logo depois do Spring Session (que e MIN_VALUE + 50) e antes do Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
class LimiteAbsolutoDeSessao extends OncePerRequestFilter {

	static final Duration LIMITE = Duration.ofDays(30);

	private final Clock relogio;

	LimiteAbsolutoDeSessao(Clock relogio) {
		this.relogio = relogio;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		var sessao = req.getSession(false);
		if (sessao != null && Instant.ofEpochMilli(sessao.getCreationTime()).plus(LIMITE).isBefore(relogio.instant())) {
			sessao.invalidate();
			req.setAttribute(RespostasDeSeguranca.ATRIBUTO_SESSAO_EXPIRADA, Boolean.TRUE);
		}
		chain.doFilter(req, res);
	}
}
