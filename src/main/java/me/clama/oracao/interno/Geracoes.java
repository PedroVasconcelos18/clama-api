package me.clama.oracao.interno;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Uso da Claude por chamada bem-sucedida (D14). Sem FK no pedido: a exclusao LGPD apaga o pedido e a linha fica. */
@Component
class Geracoes {

	private final JdbcClient jdbc;

	Geracoes(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Entra na transacao de quem chama. */
	void registrar(UUID pedidoId, ClienteClaude.Uso u, Instant agora) {
		jdbc.sql("""
				insert into oracao_geracao (pedido_id, criado_em, modelo, tokens_entrada, tokens_saida, tokens_cache_escrita,
				  tokens_cache_leitura, duracao_ms) values (:p, :em, :m, :e, :s, :ce, :cl, :d)""")
				.param("p", pedidoId).param("em", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC)).param("m", u.modelo())
				.param("e", u.tokensEntrada()).param("s", u.tokensSaida()).param("ce", u.tokensCacheEscrita())
				.param("cl", u.tokensCacheLeitura()).param("d", u.duracaoMs()).update();
	}
}
