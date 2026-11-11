package me.clama.pagamento.interno.relatorio;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.infra.web.FiltrosDeAdmin;
import me.clama.pedido.Doador;
import me.clama.pedido.PedidoApi;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relatorio de repasse de uma instituicao num periodo (INV:153-157, D3 e D4). A fonte e o ledger ({@code repasse}),
 * nunca o pedido: valor, percentual e data sao a fotografia do pagamento. Fora o {@code CANCELADO} (estorno). Ordem
 * {@code criado_em, id}. O nome do doador vem de {@link PedidoApi#doadores} e sai abreviado ("Maria S."); o nome
 * inteiro e o e-mail so existem em {@link #interna}, que o controller audita.
 */
@Component
public class RelatorioDeRepasse {

	static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	static final String SEM_NOME = "(sem nome)";

	/** Uma doacao da instituicao. Sem PII alem do nome abreviado. */
	public record Linha(UUID pedidoId, String doador, String referencia, LocalDate data, long valorDoadoCentavos, int percentual,
			long valorRepasseCentavos, String status) {}

	public record Previa(long instituicaoId, String instituicao, LocalDate de, LocalDate ate, List<Linha> linhas) {
		public int quantidade() { return linhas.size(); }
		public long totalDoadoCentavos() { return linhas.stream().mapToLong(Linha::valorDoadoCentavos).sum(); }
		public long totalRepasseCentavos() { return linhas.stream().mapToLong(Linha::valorRepasseCentavos).sum(); }
		/** Ainda por transferir: so os {@code REGISTRADO}. */
		public int pendentes() { return (int) linhas.stream().filter(l -> l.status().equals("REGISTRADO")).count(); }
		public long totalPendenteCentavos() {
			return linhas.stream().filter(l -> l.status().equals("REGISTRADO")).mapToLong(Linha::valorRepasseCentavos).sum();
		}
	}

	/** Linha do CSV interno: PII inteira. */
	public record LinhaInterna(UUID pedidoId, String nome, String email, String mpPagamentoId, String statusDoPedido,
			LocalDate pedidoCriadoEm, LocalDate data, long valorNoLedgerCentavos, long valorAtualDoPedidoCentavos, int percentual,
			long valorRepasseCentavos, String statusDoRepasse, LocalDate transferidoEm, String comprovante) {

		/** Base do ledger diferente do valor atual do pedido (nao deveria acontecer: o valor nao muda depois do pagamento). */
		public String divergencia() {
			return valorAtualDoPedidoCentavos == valorNoLedgerCentavos ? ""
					: "pedido hoje vale " + valorAtualDoPedidoCentavos + " centavos, ledger registrou " + valorNoLedgerCentavos;
		}
	}

	public record Interna(long instituicaoId, String instituicao, LocalDate de, LocalDate ate, List<LinhaInterna> linhas) {
		public int quantidade() { return linhas.size(); }
		public long totalDoadoCentavos() { return linhas.stream().mapToLong(LinhaInterna::valorNoLedgerCentavos).sum(); }
		public long totalRepasseCentavos() { return linhas.stream().mapToLong(LinhaInterna::valorRepasseCentavos).sum(); }
	}

	private record Registro(UUID pedidoId, long valorBase, int percentual, long valor, String status, java.time.Instant criadoEm,
			java.time.Instant transferidoEm, String comprovante, String mpPagamentoId) {}

	private static final String SELECT = """
			select r.pedido_id, r.valor_base_centavos, r.percentual, r.valor_centavos, r.status, r.criado_em, r.transferido_em,
			r.comprovante, c.mp_pagamento_id
			from repasse r left join cobranca c on c.pedido_id = r.pedido_id and c.paga_em is not null
			where r.instituicao_id = :inst and r.status <> 'CANCELADO' and r.criado_em >= :de and r.criado_em < :ate
			order by r.criado_em, r.id""";

	private final JdbcClient jdbc;
	private final CatalogoApi catalogo;
	private final PedidoApi pedidos;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;

	RelatorioDeRepasse(JdbcClient jdbc, CatalogoApi catalogo, PedidoApi pedidos, Auditoria auditoria, TransactionTemplate transacao) {
		this.auditoria = auditoria;
		this.transacao = transacao;
		this.jdbc = jdbc;
		this.catalogo = catalogo;
		this.pedidos = pedidos;
	}

	/** Nome da instituicao (inclusive inativa); inexistente, 404. */
	public String nomeDaInstituicao(long id) {
		var i = catalogo.instituicoes(List.of(id)).get(id);
		if (i == null) {
			throw new ErroDeNegocio(HttpStatus.NOT_FOUND, "instituicao_nao_encontrada", "Instituição não encontrada.",
					"Essa instituição não foi encontrada.");
		}
		return i.nome();
	}

	/** Ids das instituicoes (ativas ou nao) com repasse nao cancelado no periodo. */
	public List<Long> instituicoesComRepasse(Ciclo periodo) {
		return jdbc.sql("select distinct instituicao_id from repasse where status <> 'CANCELADO' and criado_em >= :de and criado_em < :ate order by 1")
				.param("de", Timestamp.from(FiltrosDeAdmin.inicio(periodo.de()))).param("ate", Timestamp.from(FiltrosDeAdmin.fimExclusivo(periodo.ate())))
				.query(Long.class).list();
	}

	/** Periodo sem repasse gera o relatorio zerado, nao erro (a prestacao de contas zerada prova que nada faltou). */
	public Previa previa(long instituicaoId, Ciclo periodo) {
		var nome = nomeDaInstituicao(instituicaoId);
		var registros = registros(instituicaoId, periodo);
		var nomes = pedidos.nomes(registros.stream().map(Registro::pedidoId).toList());
		var linhas = registros.stream().map(r -> {
			return new Linha(r.pedidoId(), abreviar(nomes.get(r.pedidoId())), referencia(r.pedidoId()), dia(r.criadoEm()), r.valorBase(),
					r.percentual(), r.valor(), r.status());
		}).toList();
		return new Previa(instituicaoId, nome, periodo.de(), periodo.ate(), linhas);
	}

	/** Nome inteiro, e-mail e id do pagamento no gateway. Quem chama grava a auditoria. */
	public Interna interna(long instituicaoId, Ciclo periodo) {
		var nome = nomeDaInstituicao(instituicaoId);
		var registros = registros(instituicaoId, periodo);
		var ids = registros.stream().map(Registro::pedidoId).toList();
		var doadores = pedidos.doadores(ids);
		var resumos = pedidos.resumos(ids);
		var linhas = registros.stream().map(r -> {
			var d = doadores.getOrDefault(r.pedidoId(), new Doador(null, null));
			var p = resumos.get(r.pedidoId());
			return new LinhaInterna(r.pedidoId(), d.nome() == null || d.nome().isBlank() ? SEM_NOME : d.nome(), d.email() == null ? "" : d.email(),
					r.mpPagamentoId(), p == null ? "" : p.status().name(), p == null ? null : dia(p.criadoEm()), dia(r.criadoEm()), r.valorBase(),
					p == null ? 0 : p.valorCentavos(), r.percentual(), r.valor(), r.status(), r.transferidoEm() == null ? null : dia(r.transferidoEm()),
					r.comprovante() == null ? "" : r.comprovante());
		}).toList();
		return new Interna(instituicaoId, nome, periodo.de(), periodo.ate(), linhas);
	}

	/** {@link #interna} mais a linha de auditoria do download (exporta PII): sem ela, nada sai. So ids e datas, nunca nome. */
	public Interna internaAuditada(Long autorId, long instituicaoId, Ciclo periodo) {
		var relatorio = interna(instituicaoId, periodo);
		transacao.executeWithoutResult(s -> auditoria.registrar(autorId, "relatorio_interno", String.valueOf(instituicaoId), Acao.ADICAO,
				Map.of("instituicaoId", instituicaoId, "de", periodo.de().toString(), "ate", periodo.ate().toString(), "linhas", relatorio.quantidade())));
		return relatorio;
	}

	private List<Registro> registros(long instituicaoId, Ciclo periodo) {
		return jdbc.sql(SELECT).param("inst", instituicaoId).param("de", Timestamp.from(FiltrosDeAdmin.inicio(periodo.de())))
				.param("ate", Timestamp.from(FiltrosDeAdmin.fimExclusivo(periodo.ate()))).query((rs, i) -> registro(rs)).list();
	}

	private static Registro registro(ResultSet rs) throws SQLException {
		var transferido = rs.getTimestamp("transferido_em");
		var mp = rs.getObject("mp_pagamento_id", Long.class);
		return new Registro(rs.getObject("pedido_id", UUID.class), rs.getLong("valor_base_centavos"), rs.getInt("percentual"),
				rs.getLong("valor_centavos"), rs.getString("status"), rs.getTimestamp("criado_em").toInstant(),
				transferido == null ? null : transferido.toInstant(), rs.getString("comprovante"), mp == null ? null : String.valueOf(mp));
	}

	private static LocalDate dia(java.time.Instant instante) {
		return instante.atZone(SAO_PAULO).toLocalDate();
	}

	/** "Maria Silva Santos" vira "Maria S."; um nome so fica como esta; vazio vira "(sem nome)" (o {@code split()} do Django). */
	static String abreviar(String nome) {
		if (nome == null) return SEM_NOME;
		var partes = java.util.Arrays.stream(nome.split("[\\p{javaWhitespace}\\p{Z}]+")).filter(p -> !p.isEmpty()).toList();
		if (partes.isEmpty()) return SEM_NOME;
		var saida = partes.size() == 1 ? partes.get(0) : partes.get(0) + " " + new String(Character.toChars(partes.get(partes.size() - 1).codePointAt(0))).toUpperCase(java.util.Locale.ROOT) + ".";
		// quem digita e-mail ou CPF no campo de nome nao pode vazar para a instituicao
		return nome.chars().anyMatch(c -> c == '@' || Character.isDigit(c)) ? SEM_NOME : saida;
	}

	/** {@code #a3f2…8b1c}: quatro primeiros e quatro ultimos hex do id, sem PII. */
	static String referencia(UUID pedidoId) {
		var hex = pedidoId.toString().replace("-", "");
		return "#" + hex.substring(0, 4) + "…" + hex.substring(hex.length() - 4);
	}
}
