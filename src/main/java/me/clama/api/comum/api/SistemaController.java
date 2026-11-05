package me.clama.api.comum.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SistemaController {

	private final JdbcTemplate jdbc;

	public SistemaController(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
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
}
