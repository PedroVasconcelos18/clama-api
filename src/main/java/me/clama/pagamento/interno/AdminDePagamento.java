package me.clama.pagamento.interno;

import static me.clama.pagamento.interno.HistoricoDoPedido.instante;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.infra.web.FiltrosDeAdmin;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admin de eventos do Mercado Pago e de repasses. Tudo em SQL, como as tabelas. Cada acao que escreve audita na
 * mesma transacao; o reprocessamento roda fora de transacao (o GET ao MP nunca abre uma) e audita depois.
 */
@Service
public class AdminDePagamento {

	private static final Set<String> STATUS_DE_EVENTO = Set.of("RECEBIDO", "PROCESSADO", "IGNORADO", "ERRO");
	private static final Set<String> STATUS_DE_REPASSE = Set.of("REGISTRADO", "TRANSFERIDO", "CANCELADO");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	public record Evento(long id, String idExterno, String tipo, String mpPagamentoId, String status, String motivo,
			UUID pedidoId, Instant recebidoEm, Instant atualizadoEm, Map<String, Object> payload) {}

	public record Repasse(long id, UUID pedidoId, long instituicaoId, long valorBaseCentavos, int percentual, long valorCentavos,
			String status, Instant criadoEm, Instant transferidoEm, Instant canceladoEm, String motivo, String comprovante) {}

	/** {@code resultado}: o corpo {@code status} do webhook ({@code ok}, {@code ignored}, {@code already_paid}...). */
	public record Reprocessado(String resultado, Evento evento) {}

	private final JdbcClient jdbc;
	private final ProcessamentoDePagamento processamento;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;
	private final Clock relogio;

	AdminDePagamento(JdbcClient jdbc, ProcessamentoDePagamento processamento, Auditoria auditoria,
			TransactionTemplate transacao, Clock relogio) {
		this.jdbc = jdbc;
		this.processamento = processamento;
		this.auditoria = auditoria;
		this.transacao = transacao;
		this.relogio = relogio;
	}

	// --- eventos ---

	public Page<Evento> eventos(String status, Pageable pagina) {
		var where = "";
		if (status != null && !status.isBlank()) {
			status = status.strip().toUpperCase(Locale.ROOT);
			if (!STATUS_DE_EVENTO.contains(status)) throw FiltrosDeAdmin.invalido("status", "Use um de " + STATUS_DE_EVENTO + ".");
			where = " where status = :status";
		} else {
			status = null;
		}
		var filtro = status;
		var total = bind(jdbc.sql("select count(*) from pagamento_evento" + where), "status", filtro).query(Long.class).single();
		var itens = bind(jdbc.sql("""
				select id, id_externo, tipo, mp_pagamento_id, status, motivo, pedido_id, recebido_em, atualizado_em, null as payload
				from pagamento_evento""" + where + " order by recebido_em desc, id desc limit :n offset :o"), "status", filtro)
				.param("n", pagina.getPageSize()).param("o", pagina.getOffset()).query((rs, i) -> evento(rs, false)).list();
		return new PageImpl<>(itens, pagina, total);
	}

	public Evento evento(long id) {
		return jdbc.sql("""
				select id, id_externo, tipo, mp_pagamento_id, status, motivo, pedido_id, recebido_em, atualizado_em,
				payload::text as payload from pagamento_evento where id = :id""").param("id", id)
				.query((rs, i) -> evento(rs, true)).optional().orElseThrow(AdminDePagamento::eventoNaoEncontrado);
	}

	/** So RECEBIDO ou ERRO (409 nos terminais). A copia concorrente (reentrega) e as travas do processamento garantem um desfecho so. */
	public Reprocessado reprocessar(Long autorId, long id) {
		var antes = evento(id);
		if (!List.of("RECEBIDO", "ERRO").contains(antes.status())) {
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "evento_encerrado", "Evento " + antes.status() + " não se reprocessa.",
					"Esse evento já foi resolvido e não precisa ser reprocessado.");
		}
		var r = processamento.reprocessar(id, antes.tipo(), antes.mpPagamentoId());
		var depois = evento(id);
		var diff = new LinkedHashMap<String, Object>();
		diff.put("status", Map.of("antes", antes.status(), "depois", depois.status()));
		diff.put("resultado", r.name());
		transacao.executeWithoutResult(s -> auditoria.registrar(autorId, "pagamento_evento", String.valueOf(id), Acao.MUDANCA, diff));
		if (r == ProcessamentoDePagamento.Resultado.FALHA_TEMPORARIA) {
			throw new ErroDeNegocio(HttpStatus.BAD_GATEWAY, "mp_indisponivel", "Reprocessamento sem resposta.",
					"Não deu para reprocessar agora. Tente de novo em instantes.");
		}
		return new Reprocessado(r.status, depois);
	}

	private static Evento evento(java.sql.ResultSet rs, boolean comPayload) throws java.sql.SQLException {
		Map<String, Object> payload = null;
		if (comPayload && rs.getString("payload") != null) {
			@SuppressWarnings("unchecked")
			var m = (Map<String, Object>) JSON.readValue(rs.getString("payload"), Map.class);
			payload = m;
		}
		return new Evento(rs.getLong("id"), rs.getString("id_externo"), rs.getString("tipo"), rs.getString("mp_pagamento_id"),
				rs.getString("status"), rs.getString("motivo"), rs.getObject("pedido_id", UUID.class), instante(rs, "recebido_em"),
				instante(rs, "atualizado_em"), payload);
	}

	private static ErroDeNegocio eventoNaoEncontrado() {
		return new ErroDeNegocio(HttpStatus.NOT_FOUND, "evento_nao_encontrado", "Evento não encontrado.", "Esse evento não foi encontrado.");
	}

	// --- repasses ---

	public Page<Repasse> repasses(String instituicaoId, String status, String de, String ate, Pageable pagina) {
		var inst = FiltrosDeAdmin.numero("instituicaoId", instituicaoId);
		var dia1 = FiltrosDeAdmin.dia("de", de);
		var dia2 = FiltrosDeAdmin.dia("ate", ate);
		FiltrosDeAdmin.ordemDoPeriodo(dia1, dia2);
		if (status != null && !status.isBlank()) {
			status = status.strip().toUpperCase(Locale.ROOT);
			if (!STATUS_DE_REPASSE.contains(status)) throw FiltrosDeAdmin.invalido("status", "Use um de " + STATUS_DE_REPASSE + ".");
		} else {
			status = null;
		}
		var cond = new ArrayList<String>();
		var params = new LinkedHashMap<String, Object>();
		if (inst != null) { cond.add("instituicao_id = :inst"); params.put("inst", inst); }
		if (status != null) { cond.add("status = :status"); params.put("status", status); }
		if (dia1 != null) { cond.add("criado_em >= :de"); params.put("de", Timestamp.from(FiltrosDeAdmin.inicio(dia1))); }
		if (dia2 != null) { cond.add("criado_em < :ate"); params.put("ate", Timestamp.from(FiltrosDeAdmin.fimExclusivo(dia2))); }
		var where = cond.isEmpty() ? "" : " where " + String.join(" and ", cond);
		var total = jdbc.sql("select count(*) from repasse" + where).params(params).query(Long.class).single();
		var itens = jdbc.sql(COLUNAS_DE_REPASSE + where + " order by criado_em desc, id desc limit :n offset :o").params(params)
				.param("n", pagina.getPageSize()).param("o", pagina.getOffset()).query((rs, i) -> repasse(rs)).list();
		return new PageImpl<>(itens, pagina, total);
	}

	private static final String COLUNAS_DE_REPASSE = """
			select id, pedido_id, instituicao_id, valor_base_centavos, percentual, valor_centavos, status, criado_em,
			transferido_em, cancelado_em, motivo, comprovante from repasse""";

	/**
	 * REGISTRADO vira TRANSFERIDO. TRANSFERIDO de novo e no-op (sem nova auditoria); CANCELADO e 409; inexistente, 404.
	 * O UPDATE so pega REGISTRADO, entao um estorno concorrente (que cancela) nunca e sobrescrito.
	 */
	public Repasse transferir(Long autorId, long id, String comprovante) {
		return transacao.execute(s -> {
			var agora = relogio.instant();
			var mudou = jdbc.sql("update repasse set status = 'TRANSFERIDO', transferido_em = :em, comprovante = :comprovante where id = :id and status = 'REGISTRADO'")
					.param("em", Timestamp.from(agora)).param("comprovante", comprovante).param("id", id).update() == 1;
			var r = jdbc.sql(COLUNAS_DE_REPASSE + " where id = :id").param("id", id).query((rs, i) -> repasse(rs)).optional()
					.orElseThrow(() -> new ErroDeNegocio(HttpStatus.NOT_FOUND, "repasse_nao_encontrado", "Repasse não encontrado.",
							"Esse repasse não foi encontrado."));
			if (mudou) {
				auditoria.registrar(autorId, "repasse", String.valueOf(id), Acao.MUDANCA,
						Map.of("status", Map.of("antes", "REGISTRADO", "depois", "TRANSFERIDO"), "comprovante", true));
			} else if ("CANCELADO".equals(r.status())) {
				throw new ErroDeNegocio(HttpStatus.CONFLICT, "repasse_cancelado", "Repasse cancelado não se transfere.",
						"Esse repasse foi cancelado (estorno) e não pode ser marcado como transferido.");
			}
			return r;
		});
	}

	/** O que o lote quitou: quantos repasses e a soma deles. */
	public record Quitacao(int quantidade, long totalCentavos) {}

	/**
	 * Quitacao em lote (D8): os {@code REGISTRADO} da instituicao cujo repasse e do periodo (dias de Sao Paulo, inclusivos).
	 * {@code quantidade} e a da previa: se agora forem outras, 409 {@code recorte_mudou} e nada muda. As linhas sao travadas
	 * em ordem de id ({@code FOR UPDATE}): um lote concorrente espera, relê o status ja {@code TRANSFERIDO} e acha zero, entao
	 * cai no 409 em vez de quitar duas vezes. {@code CANCELADO} e {@code TRANSFERIDO} nao entram. Audita cada repasse (como
	 * o individual) e o lote.
	 */
	public Quitacao transferirLote(Long autorId, long instituicaoId, LocalDate de, LocalDate ate, String comprovante, int quantidade) {
		if (comprovante == null || comprovante.isBlank() || comprovante.length() > 120) {
			throw FiltrosDeAdmin.invalido("comprovante", "Informe o comprovante, com até 120 caracteres.");
		}
		FiltrosDeAdmin.ordemDoPeriodo(de, ate);
		return transacao.execute(s -> {
			var agora = relogio.instant();
			var ids = jdbc.sql("""
					select id from repasse where instituicao_id = :inst and status = 'REGISTRADO' and criado_em >= :de and criado_em < :ate
					order by id for update""").param("inst", instituicaoId).param("de", Timestamp.from(FiltrosDeAdmin.inicio(de)))
					.param("ate", Timestamp.from(FiltrosDeAdmin.fimExclusivo(ate))).query(Long.class).list();
			if (ids.size() != quantidade) {
				throw new ErroDeNegocio(HttpStatus.CONFLICT, "recorte_mudou", "Os repasses do período mudaram desde a prévia.",
						"Os repasses desse período mudaram desde a prévia. Confira de novo antes de marcar.");
			}
			if (ids.isEmpty()) return new Quitacao(0, 0);
			var total = jdbc.sql("select coalesce(sum(valor_centavos), 0) from repasse where id in (:ids)").param("ids", ids).query(Long.class).single();
			var mudou = jdbc.sql("update repasse set status = 'TRANSFERIDO', transferido_em = :em, comprovante = :comprovante where id in (:ids) and status = 'REGISTRADO'")
					.param("em", Timestamp.from(agora)).param("comprovante", comprovante).param("ids", ids).update();
			if (mudou != ids.size()) throw new IllegalStateException("lote de repasses mudou sob trava");
			for (var id : ids) {
				auditoria.registrar(autorId, "repasse", String.valueOf(id), Acao.MUDANCA,
						Map.of("status", Map.of("antes", "REGISTRADO", "depois", "TRANSFERIDO"), "comprovante", true));
			}
			auditoria.registrar(autorId, "repasse_lote", String.valueOf(instituicaoId), Acao.MUDANCA,
					Map.of("de", de.toString(), "ate", ate.toString(), "quantidade", ids.size(), "totalCentavos", total, "comprovante", true));
			return new Quitacao(ids.size(), total);
		});
	}

	private static Repasse repasse(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Repasse(rs.getLong("id"), rs.getObject("pedido_id", UUID.class), rs.getLong("instituicao_id"),
				rs.getLong("valor_base_centavos"), rs.getInt("percentual"), rs.getLong("valor_centavos"), rs.getString("status"),
				instante(rs, "criado_em"), instante(rs, "transferido_em"), instante(rs, "cancelado_em"), rs.getString("motivo"), rs.getString("comprovante"));
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, String nome, String valor) {
		return valor == null ? spec : spec.param(nome, valor);
	}
}
