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

	/** {@code custoEstimadoUsd} nulo = falta algum {@code clama.oracao.preco-por-milhao.*}. */
	public record Resumo(long geracoes, long tokensEntrada, long tokensSaida, long tokensCacheEscrita, long tokensCacheLeitura,
			BigDecimal custoEstimadoUsd, Duracao duracao) {}

	private final JdbcClient jdbc;
	private final OracaoProps props;

	MetricasDaOracao(JdbcClient jdbc, OracaoProps props) {
		this.jdbc = jdbc;
		this.props = props;
	}

	public Resumo resumo(Periodo p) {
		var ini = OffsetDateTime.ofInstant(p.inicio(), ZoneOffset.UTC);
		var fim = OffsetDateTime.ofInstant(p.fimExclusivo(), ZoneOffset.UTC);
		return jdbc.sql("""
				select count(*) n, coalesce(sum(tokens_entrada), 0) e, coalesce(sum(tokens_saida), 0) s,
				  coalesce(sum(tokens_cache_escrita), 0) ce, coalesce(sum(tokens_cache_leitura), 0) cl,
				  percentile_cont(0.5) within group (order by duracao_ms) p50,
				  percentile_cont(0.9) within group (order by duracao_ms) p90
				from oracao_geracao where criado_em >= :ini and criado_em < :fim""")
				.param("ini", ini).param("fim", fim).query((r, i) -> {
					long e = r.getLong("e"), s = r.getLong("s"), ce = r.getLong("ce"), cl = r.getLong("cl");
					return new Resumo(r.getLong("n"), e, s, ce, cl, custo(e, s, ce, cl),
							new Duracao((Double) r.getObject("p50"), (Double) r.getObject("p90")));
				}).single();
	}

	private BigDecimal custo(long entrada, long saida, long cacheEscrita, long cacheLeitura) {
		var pr = props.precoPorMilhao();
		if (pr == null || !pr.completo()) return null;
		return pr.entrada().multiply(BigDecimal.valueOf(entrada)).add(pr.saida().multiply(BigDecimal.valueOf(saida)))
				.add(pr.cacheEscrita().multiply(BigDecimal.valueOf(cacheEscrita)))
				.add(pr.cacheLeitura().multiply(BigDecimal.valueOf(cacheLeitura)))
				.divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
	}
}
