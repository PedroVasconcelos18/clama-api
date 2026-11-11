package me.clama.pagamento.interno;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tabela {@code pagamento_evento} em SQL. Fora de transacao cada comando e autocommit; dentro, entra na do chamador.
 * As marcacoes so mudam linha nao terminal (RECEBIDO/ERRO): a copia concorrente que perde nao sobrescreve a que ganhou,
 * e o retorno diz se a linha mudou.
 */
@Component
class EventosDePagamento {

	static final String PROCESSADO = "PROCESSADO";
	static final String IGNORADO = "IGNORADO";
	static final String ERRO = "ERRO";

	record Evento(long id, String status) {
		boolean terminal() {
			return PROCESSADO.equals(status) || IGNORADO.equals(status);
		}
	}

	private final JdbcTemplate jdbc;

	EventosDePagamento(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** {@code INSERT ... ON CONFLICT DO NOTHING} e leitura: a linha nova (RECEBIDO) ou a que ja existia, com o primeiro payload. */
	Evento registrar(String idExterno, String tipo, String mpPagamentoId, String payloadJson, Instant agora) {
		var quando = Timestamp.from(agora);
		jdbc.update("""
				INSERT INTO pagamento_evento (id_externo, tipo, mp_pagamento_id, payload, status, recebido_em, atualizado_em)
				VALUES (?, ?, ?, ?::jsonb, 'RECEBIDO', ?, ?)
				ON CONFLICT (id_externo) DO NOTHING""", idExterno, cortar(tipo, 60), cortar(mpPagamentoId, 64), payloadJson,
				quando, quando);
		return jdbc.queryForObject("SELECT id, status FROM pagamento_evento WHERE id_externo = ?",
				(rs, n) -> new Evento(rs.getLong(1), rs.getString(2)), idExterno);
	}

	/** {@code motivo}: codigo curto, sem PII. {@code pedidoId} nulo mantem o que ja estava. */
	boolean marcar(long id, String status, String motivo, UUID pedidoId, Instant agora) {
		return jdbc.update("""
				UPDATE pagamento_evento SET status = ?, motivo = ?, pedido_id = coalesce(?, pedido_id), atualizado_em = ?
				WHERE id = ? AND status IN ('RECEBIDO', 'ERRO')""", status, cortar(motivo, 200), pedidoId,
				Timestamp.from(agora), id) == 1;
	}

	static String cortar(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}
}
