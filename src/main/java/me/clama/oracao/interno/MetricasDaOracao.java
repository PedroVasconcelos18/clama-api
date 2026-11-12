package me.clama.oracao.interno;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import me.clama.infra.web.FiltrosDeAdmin.Periodo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Tokens e custo estimado das geracoes no periodo (D14). */
@Component
public class MetricasDaOracao {

	public record Duracao(Double p50Ms, Double p90Ms) {}

	/** {@code custoEstimadoUsd} nulo = algum modelo do periodo nao tem preco completo em {@code clama.oracao.precos.<modelo>.*}. */
	public record ResumoDaOracao(long geracoes, long tokensEntrada, long tokensSaida, long tokensCacheEscrita, long tokensCacheLeitura,
			BigDecimal custoEstimadoUsd, Duracao duracao) {}

	private final JdbcClient jdbc;
	private final OracaoProps props;

	MetricasDaOracao(JdbcClient jdbc, OracaoProps props) {
		this.jdbc = jdbc;
		this.props = props;
	}

	public ResumoDaOracao resumo(Periodo p) {
		var ini = OffsetDateTime.ofInstant(p.inicio(), ZoneOffset.UTC);
		var fim = OffsetDateTime.ofInstant(p.fimExclusivo(), ZoneOffset.UTC);
		var duracao = jdbc.sql("""
				select percentile_cont(0.5) within group (order by duracao_ms) p50,
				  percentile_cont(0.9) within group (order by duracao_ms) p90
				from oracao_geracao where criado_em >= :ini and criado_em < :fim""")
				.param("ini", ini).param("fim", fim)
				.query((r, i) -> new Duracao((Double) r.getObject("p50"), (Double) r.getObject("p90"))).single();
		// um grupo por modelo: cada um e precificado pelo seu preco; sem preco completo de algum, o total e nulo
		var grupos = jdbc.sql("""
				select modelo, count(*) n, sum(tokens_entrada) e, sum(tokens_saida) s, sum(tokens_cache_escrita) ce,
				  sum(tokens_cache_leitura) cl
				from oracao_geracao where criado_em >= :ini and criado_em < :fim group by modelo""")
				.param("ini", ini).param("fim", fim).query((r, i) -> new Grupo(r.getString("modelo"), r.getLong("n"),
						r.getLong("e"), r.getLong("s"), r.getLong("ce"), r.getLong("cl"))).list();
		long n = 0, e = 0, s = 0, ce = 0, cl = 0;
		BigDecimal custo = BigDecimal.ZERO;
		for (var g : grupos) {
			n += g.n(); e += g.e(); s += g.s(); ce += g.ce(); cl += g.cl();
			var parcial = custo == null ? null : custo(g);
			custo = parcial == null ? null : custo.add(parcial);
		}
		return new ResumoDaOracao(n, e, s, ce, cl, custo == null ? null : custo.divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP), duracao);
	}

	private record Grupo(String modelo, long n, long e, long s, long ce, long cl) {}

	/** Custo do grupo em USD vezes 1e6 (ainda nao dividido), ou nulo se o modelo nao tem preco completo. */
	private BigDecimal custo(Grupo g) {
		var pr = props.precos() == null ? null : props.precos().get(g.modelo());
		if (pr == null || !pr.completo()) return null;
		return pr.entrada().multiply(BigDecimal.valueOf(g.e())).add(pr.saida().multiply(BigDecimal.valueOf(g.s())))
				.add(pr.cacheEscrita().multiply(BigDecimal.valueOf(g.ce())))
				.add(pr.cacheLeitura().multiply(BigDecimal.valueOf(g.cl())));
	}
}
