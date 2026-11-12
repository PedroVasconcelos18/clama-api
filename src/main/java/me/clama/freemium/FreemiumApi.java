package me.clama.freemium;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.freemium.interno.Bloqueios;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Interface do modulo para os outros (hoje so a LGPD). */
@Service
public class FreemiumApi {

	private final Bloqueios bloqueios;
	private final JdbcClient jdbc;

	FreemiumApi(Bloqueios bloqueios, JdbcClient jdbc) {
		this.bloqueios = bloqueios;
		this.jdbc = jdbc;
	}

	/** Esse e-mail (HMAC) ou algum desses CPFs (HMAC) ja usou a oracao gratuita? So sim ou nao: o hash nao diz nada ao titular (R22). */
	@Transactional(readOnly = true)
	public boolean bloqueado(String emailHmac, Collection<String> cpfHmacs) {
		if (bloqueios.existe(emailHmac, null)) return true;
		return cpfHmacs.stream().anyMatch(c -> bloqueios.existe(null, c));
	}

	/** Os links de confirmacao de cada pedido (quando, vencimento, uso), sem o hash; pedido sem link fica fora do mapa. */
	@Transactional(readOnly = true)
	public Map<UUID, List<Map<String, Object>>> confirmacoes(Collection<UUID> pedidoIds) {
		if (pedidoIds.isEmpty()) return Map.of();
		var saida = new LinkedHashMap<UUID, List<Map<String, Object>>>();
		jdbc.sql("select pedido_id, criado_em, expira_em, usado_em from freemium_confirmacao where pedido_id in (:ids) order by criado_em, id")
				.param("ids", pedidoIds).query((rs, n) -> {
					var m = new LinkedHashMap<String, Object>();
					m.put("criadoEm", rs.getObject("criado_em", OffsetDateTime.class).toInstant());
					m.put("expiraEm", rs.getObject("expira_em", OffsetDateTime.class).toInstant());
					var usado = rs.getObject("usado_em", OffsetDateTime.class);
					m.put("usadoEm", usado == null ? null : usado.toInstant());
					saida.computeIfAbsent(rs.getObject("pedido_id", UUID.class), k -> new java.util.ArrayList<>()).add(m);
					return 1;
				}).list();
		return saida;
	}
}
