package me.clama.freemium.interno;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Link de confirmacao: o token (32 bytes, base64url) so existe no e-mail; o banco guarda o SHA-256. Cada envio do job
 * emite um token novo e os anteriores seguem validos.
 */
@Component
public class Confirmacoes {

	/** Uma linha de {@code freemium_confirmacao}; {@code usadoEm} nulo = ainda nao usado. */
	public record Confirmacao(long id, UUID pedidoId, Instant expiraEm, Instant usadoEm) {
		public boolean usado() { return usadoEm != null; }

		public boolean vencido(Instant agora) { return !expiraEm.isAfter(agora); }
	}

	private static final SecureRandom ALEATORIO = new SecureRandom();

	private final JdbcClient jdbc;
	private final Clock relogio;
	private final FreemiumProps props;

	Confirmacoes(JdbcClient jdbc, Clock relogio, FreemiumProps props) {
		this.jdbc = jdbc;
		this.relogio = relogio;
		this.props = props;
	}

	public static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // todo JDK traz SHA-256
		}
	}

	/** Grava so o hash (validade de {@code validadeLink}) e devolve o token em claro, para o e-mail. Transacao curta propria. */
	@Transactional
	String emitir(UUID pedidoId) {
		var bytes = new byte[32];
		ALEATORIO.nextBytes(bytes);
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		var agora = relogio.instant();
		jdbc.sql("insert into freemium_confirmacao (pedido_id, hash, criado_em, expira_em) values (:p, :h, :agora, :expira)")
				.param("p", pedidoId).param("h", hash(token)).param("agora", utc(agora)).param("expira", utc(agora.plus(props.validadeLink())))
				.update();
		return token;
	}

	/**
	 * Para a saga (T3): acha o link pelo hash do token e trava a linha ({@code FOR UPDATE}) ate o fim da transacao do
	 * chamador, que precisa existir. Vazio = nao existe (link_invalido).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Confirmacao> travarPorHash(String hash) {
		return jdbc.sql("select id, pedido_id, expira_em, usado_em from freemium_confirmacao where hash = :h for update")
				.param("h", hash)
				.query((rs, i) -> new Confirmacao(rs.getLong("id"), rs.getObject("pedido_id", UUID.class),
						rs.getObject("expira_em", OffsetDateTime.class).toInstant(),
						rs.getObject("usado_em", OffsetDateTime.class) == null ? null : rs.getObject("usado_em", OffsetDateTime.class).toInstant()))
				.optional();
	}

	/** Gasta o link travado por {@link #travarPorHash}. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void marcarUsado(long id) {
		jdbc.sql("update freemium_confirmacao set usado_em = :agora where id = :id").param("agora", utc(relogio.instant())).param("id", id).update();
	}

	private static OffsetDateTime utc(Instant i) {
		return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
	}
}
