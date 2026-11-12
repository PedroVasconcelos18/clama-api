package me.clama.infra.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * INV:20. So responde para os Hosts de {@code clama.hosts-permitidos} (CLAMA_HOSTS_PERMITIDOS); os demais levam 421 sem corpo.
 * Decisoes:
 * - olha o header Host cru (o que o proxy que conectou mandou), nao o X-Forwarded-Host: o rewrite do Vercel e a Cloudflare
 *   entregam Host: api.clama.me, entao nenhum host do front entra na lista, e um XFH forjado nao abre nada;
 * - /actuator/health/** passa com qualquer Host: o healthcheck do Railway chega com Host proprio (healthcheck.railway.app);
 * - compara sem porta e sem diferenciar maiusculas.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class FiltroDeHostPermitido extends OncePerRequestFilter {

	private final Set<String> permitidos;

	FiltroDeHostPermitido(@Value("${clama.hosts-permitidos:api.clama.me}") String permitidos) {
		this.permitidos = Arrays.stream(permitidos.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT))
				.filter(s -> !s.isEmpty()).collect(Collectors.toSet());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
			throws ServletException, IOException {
		if (req.getRequestURI().startsWith("/actuator/health") || permitidos.contains(semPorta(req.getHeader("Host") != null ? req.getHeader("Host") : req.getServerName()))) {
			chain.doFilter(req, resp);
			return;
		}
		resp.setStatus(421); // Misdirected Request; sem corpo, nada a vazar
	}

	static String semPorta(String host) {
		if (host == null) return "";
		host = host.strip().toLowerCase(Locale.ROOT);
		int fim = host.lastIndexOf(':');
		return fim > host.lastIndexOf(']') && fim >= 0 ? host.substring(0, fim) : host;
	}
}
