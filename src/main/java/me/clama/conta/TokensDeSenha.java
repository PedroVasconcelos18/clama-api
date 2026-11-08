package me.clama.conta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Link de senha de uso unico: o token (32 bytes aleatorios, base64url) so existe no e-mail; o banco guarda o SHA-256.
 * Emitir um novo invalida os anteriores da mesma finalidade. Consumir e um UPDATE atomico: duas requisicoes com o
 * mesmo token, so uma ganha.
 */
@Component
class TokensDeSenha {

	enum Finalidade {
		REDEFINIR(Duration.ofHours(1)), DEFINIR(Duration.ofHours(72));

		final Duration validade;

		Finalidade(Duration validade) { this.validade = validade; }
	}

	private static final SecureRandom ALEATORIO = new SecureRandom();

	private final JdbcClient jdbc;
	private final Clock relogio;

	TokensDeSenha(JdbcClient jdbc, Clock relogio) {
		this.jdbc = jdbc;
		this.relogio = relogio;
	}

	static String novoToken() {
		var bytes = new byte[32];
		ALEATORIO.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // todo JDK traz SHA-256
		}
	}

	/** Devolve o token em claro. Transacao propria: tambem e chamado de listener AFTER_COMMIT, onde nao ha como escrever. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	String emitir(Long usuarioId, Finalidade finalidade) {
		var agora = agora();
		jdbc.sql("update token_de_senha set usado_em = :agora where usuario_id = :u and finalidade = :f and usado_em is null")
				.param("agora", agora).param("u", usuarioId).param("f", finalidade.name()).update();
		var token = novoToken();
		jdbc.sql("insert into token_de_senha (usuario_id, hash, finalidade, criado_em, expira_em) values (:u, :h, :f, :agora, :expira)")
				.param("u", usuarioId).param("h", hash(token)).param("f", finalidade.name()).param("agora", agora)
				.param("expira", agora.plus(finalidade.validade)).update();
		return token;
	}

	/** De quem e o token, se ainda vale (sem gastar). */
	Optional<Long> espiar(String token) {
		return jdbc.sql("select usuario_id from token_de_senha where hash = :h and usado_em is null and expira_em > :agora")
				.param("h", hash(token)).param("agora", agora()).query(Long.class).optional();
	}

	/** Gasta o token e devolve de quem era; vazio se nao existe, ja foi usado ou expirou. */
	@Transactional
	Optional<Long> consumir(String token) {
		var agora = agora();
		return jdbc.sql("update token_de_senha set usado_em = :agora where hash = :h and usado_em is null and expira_em > :agora returning usuario_id")
				.param("h", hash(token)).param("agora", agora).query(Long.class).optional();
	}

	private OffsetDateTime agora() {
		return OffsetDateTime.ofInstant(relogio.instant(), java.time.ZoneOffset.UTC);
	}
}
