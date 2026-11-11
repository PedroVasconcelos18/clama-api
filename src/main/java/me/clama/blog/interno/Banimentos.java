package me.clama.blog.interno;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Ban ativo do blog: uma linha por conta em {@code blog_banimento} (D9). Aqui so a consulta; banir e revogar sao do admin. */
@Component
public class Banimentos {

	private final JdbcClient jdbc;

	Banimentos(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** A conta esta banida (qualquer papel). */
	public boolean estaBanido(long usuarioId) {
		return jdbc.sql("select exists (select 1 from blog_banimento where usuario_id = :id)")
				.param("id", usuarioId).query(Boolean.class).single();
	}
}
