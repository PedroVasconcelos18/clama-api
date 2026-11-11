package me.clama.pagamento.interno;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import me.clama.pedido.HistoricoDePagamento;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** O que o detalhe do pedido no admin mostra do pagamento: sem copia-e-cola, QR nem payload. */
@Component
class HistoricoDoPedido implements HistoricoDePagamento {

	private final JdbcClient jdbc;
	private final Clock relogio;

	HistoricoDoPedido(JdbcClient jdbc, Clock relogio) {
		this.jdbc = jdbc;
		this.relogio = relogio;
	}

	@Override
	@Transactional(readOnly = true)
	public Historico de(UUID pedidoId) {
		var agora = relogio.instant();
		var cobrancas = jdbc.sql("""
				select n, mp_pagamento_id, criada_em, expira_em, cancelada_em, paga_em from cobranca
				where pedido_id = :p order by n desc""").param("p", pedidoId).query((rs, i) -> {
			var paga = instante(rs, "paga_em");
			var cancelada = instante(rs, "cancelada_em");
			var expira = instante(rs, "expira_em");
			var status = paga != null ? "PAGA" : cancelada != null ? "CANCELADA" : expira.isBefore(agora) ? "EXPIRADA" : "PENDENTE";
			return new CobrancaDoPedido(rs.getInt("n"), status, rs.getLong("mp_pagamento_id"), instante(rs, "criada_em"), expira,
					cancelada, paga);
		}).list();
		var repasse = jdbc.sql("""
				select id, status, valor_base_centavos, percentual, valor_centavos, criado_em, transferido_em, cancelado_em, motivo
				from repasse where pedido_id = :p""").param("p", pedidoId).query((rs, i) -> new RepasseDoPedido(rs.getLong("id"),
				rs.getString("status"), rs.getLong("valor_base_centavos"), rs.getInt("percentual"), rs.getLong("valor_centavos"),
				instante(rs, "criado_em"), instante(rs, "transferido_em"), instante(rs, "cancelado_em"), rs.getString("motivo")))
				.optional().orElse(null);
		var eventos = jdbc.sql("""
				select id, tipo, status, motivo, recebido_em from pagamento_evento
				where pedido_id = :p order by recebido_em desc, id desc""").param("p", pedidoId).query((rs, i) ->
				new EventoDoPedido(rs.getLong("id"), rs.getString("tipo"), rs.getString("status"), rs.getString("motivo"),
						instante(rs, "recebido_em"))).list();
		return new Historico(cobrancas, repasse, eventos);
	}

	static Instant instante(ResultSet rs, String coluna) throws SQLException {
		var t = rs.getTimestamp(coluna);
		return t == null ? null : t.toInstant();
	}
}
