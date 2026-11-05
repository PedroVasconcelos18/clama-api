package me.clama.api.comum.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SistemaController {

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
			banco = "error";
		}
		return Map.of("status", "ok", "version", "0.1.0", "database", banco,
				"timestamp", Instant.now().toString());
	}

	/**
	 * CSRFTokenView: o cookie e HttpOnly, entao o token vai no corpo e o front devolve no X-CSRFToken.
	 * O valor e o proprio cookie (sem mascara XOR), entao o eco no header bate direto.
	 */
	@GetMapping("/api/csrf/")
	public Map<String, String> csrf(HttpServletRequest req, HttpServletResponse resp) {
		CsrfToken token = csrf.loadToken(req);
		if (token == null) {
			token = csrf.generateToken(req);
		}
		csrf.saveToken(token, req, resp); // ensure_csrf_cookie: regrava sempre, renovando a validade
		return Map.of("csrf_token", token.getToken());
	}
}
