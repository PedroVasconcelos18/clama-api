package me.clama.api.comum.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import me.clama.api.identidade.http.CsrfDjango;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SistemaController {
	private static final Logger LOG = LoggerFactory.getLogger(SistemaController.class);

	private final JdbcTemplate jdbc;
	private final CsrfTokenRepository csrf;

	public SistemaController(JdbcTemplate jdbc, CsrfTokenRepository csrf) {
		this.jdbc = jdbc;
		this.csrf = csrf;
	}

	@GetMapping("/api/health/")
	public Map<String, String> health() {
		String banco;
		try {
			jdbc.queryForObject("SELECT 1", Integer.class);
			banco = "ok";
		} catch (Exception e) {
			LOG.warn("Health: SELECT 1 falhou", e);
			banco = "error";
		}
		return Map.of("status", "ok", "version", "0.1.0", "database", banco,
				"timestamp", Instant.now().toString());
	}

	/**
	 * CSRFTokenView: o cookie e HttpOnly, entao o token vai no corpo e o front devolve no X-CSRFToken.
	 * O valor e o segredo do cookie (sem a mascara do Django, que tambem aceita assim), entao o eco bate direto.
	 * Cookie ausente ou fora do formato do Django ganha um segredo novo, como no CsrfViewMiddleware.
	 */
	@GetMapping("/api/csrf/")
	public Map<String, String> csrf(HttpServletRequest req, HttpServletResponse resp) {
		CsrfToken salvo = csrf.loadToken(req);
		String segredo = salvo == null ? null : CsrfDjango.segredo(salvo.getToken());
		if (segredo == null) {
			segredo = CsrfDjango.novo();
		}
		// ensure_csrf_cookie: regrava sempre, renovando a validade
		csrf.saveToken(new DefaultCsrfToken("X-CSRFToken", "_csrf", segredo), req, resp);
		return Map.of("csrf_token", segredo);
	}
}
