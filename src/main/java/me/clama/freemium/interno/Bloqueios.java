package me.clama.freemium.interno;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Quem ja usou a oracao gratuita: so HMAC, nunca o valor em claro. Sem FK: sobrevive a exclusao de pedido e conta. */
@Component
public class Bloqueios {

	private final JdbcClient jdbc;

	Bloqueios(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Ha bloqueio com esse e-mail ou CPF ({@code null} nao casa). */
	public boolean existe(String emailHmac, String cpfHmac) {
		return jdbc.sql("select exists (select 1 from freemium_bloqueio where email_hmac = :e or cpf_hmac = :c)")
				.param("e", emailHmac).param("c", cpfHmac).query(Boolean.class).single();
	}

	/** Bloqueios do mesmo IP criados a partir de {@code desde} (inclusivo). */
	public int contarPorIp(String ipHmac, Instant desde) {
		return jdbc.sql("select count(*) from freemium_bloqueio where ip_hmac = :ip and criado_em >= :desde")
				.param("ip", ipHmac).param("desde", OffsetDateTime.ofInstant(desde, ZoneOffset.UTC)).query(Integer.class).single();
	}

	/** {@code false} se o e-mail ou o CPF ja estavam bloqueados (ON CONFLICT DO NOTHING: nao aborta a transacao). */
	public boolean inserir(String emailHmac, String cpfHmac, String ipHmac, Instant agora) {
		return jdbc.sql("""
				insert into freemium_bloqueio (email_hmac, cpf_hmac, ip_hmac, criado_em) values (:e, :c, :ip, :agora)
				on conflict do nothing""")
				.param("e", emailHmac).param("c", cpfHmac).param("ip", ipHmac)
				.param("agora", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC)).update() == 1;
	}
}
