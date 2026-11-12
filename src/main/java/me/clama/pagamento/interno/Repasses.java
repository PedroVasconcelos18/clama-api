package me.clama.pagamento.interno;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Repasse a instituicao do pedido (INV:99, D9). Registrado so pelo pagamento aprovado, na transacao dele; idempotente
 * pelo {@code UNIQUE (pedido_id)}. Estorno antes da transferencia cancela; depois dela, quem chamou alerta o admin.
 */
@Component
@EnableConfigurationProperties(RepasseProps.class)
public class Repasses {

	static final String CANCELADO = "repasse_cancelado";
	static final String TRANSFERIDO = "repasse_transferido";
	static final String SEM_REPASSE = "sem_repasse";

	private final JdbcTemplate jdbc;
	private final RepasseProps props;

	Repasses(JdbcTemplate jdbc, RepasseProps props) {
		this.jdbc = jdbc;
		this.props = props;
	}

	/** Percentual do valor, arredondado para baixo (20% de 3001 = 600). */
	public static long valor(long baseCentavos, int percentual) {
		return Math.floorDiv(Math.multiplyExact(baseCentavos, percentual), 100);
	}

	/** {@code true} se gravou agora; nada sem instituicao, com repasse de 0 centavo ou se o pedido ja tem repasse. */
	boolean registrar(UUID pedidoId, Long instituicaoId, long baseCentavos, Instant agora) {
		if (instituicaoId == null) return false;
		var percentual = props.percentual();
		var valor = valor(baseCentavos, percentual);
		if (valor <= 0) return false;
		return jdbc.update("""
				INSERT INTO repasse (pedido_id, instituicao_id, valor_base_centavos, percentual, valor_centavos, status, criado_em)
				VALUES (?, ?, ?, ?, ?, 'REGISTRADO', ?)
				ON CONFLICT (pedido_id) DO NOTHING""", pedidoId, instituicaoId, baseCentavos, percentual, valor,
				java.sql.Timestamp.from(agora)) == 1;
	}

	/**
	 * Estorno do pagamento do pedido: REGISTRADO vira CANCELADO (com {@code motivo}, o status do MP). Devolve o desfecho,
	 * que vai para o evento: {@link #CANCELADO}, {@link #TRANSFERIDO} (nada muda: acerto no proximo ciclo) ou
	 * {@link #SEM_REPASSE} (nao havia, ou ja estava cancelado).
	 */
	String cancelar(UUID pedidoId, String motivo, Instant agora) {
		if (jdbc.update("UPDATE repasse SET status = 'CANCELADO', cancelado_em = ?, motivo = ? WHERE pedido_id = ? AND status = 'REGISTRADO'",
				java.sql.Timestamp.from(agora), motivo, pedidoId) == 1) {
			return CANCELADO;
		}
		return jdbc.queryForList("SELECT status FROM repasse WHERE pedido_id = ?", String.class, pedidoId).contains("TRANSFERIDO")
				? TRANSFERIDO : SEM_REPASSE;
	}

	/** Dos pedidos dados, os que tem repasse (qualquer status). */
	java.util.Set<UUID> pedidosComRepasse(java.util.Collection<UUID> pedidoIds) {
		var marcas = String.join(",", java.util.Collections.nCopies(pedidoIds.size(), "?"));
		return new java.util.HashSet<>(jdbc.queryForList("SELECT pedido_id FROM repasse WHERE pedido_id IN (" + marcas + ")", UUID.class,
				pedidoIds.toArray()));
	}

	long valorCentavos(UUID pedidoId) {
		return jdbc.queryForObject("SELECT valor_centavos FROM repasse WHERE pedido_id = ?", Long.class, pedidoId);
	}
}
