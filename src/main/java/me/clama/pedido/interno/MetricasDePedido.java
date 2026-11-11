package me.clama.pedido.interno;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import me.clama.catalogo.CatalogoApi;
import me.clama.infra.web.FiltrosDeAdmin.Periodo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Metricas de pedido (D14). O periodo ja vem cortado em dias de SP. "Pago" = {@code pago_em} no periodo e nao gratuito
 * (gratuito, freemium confirmado incluso, conta a parte); "enviados" e a latencia olham {@code enviada_em}, gravado so no
 * primeiro envio. Estorno nao muda o pedido, entao entra no faturamento (limite conhecido, 4a D9).
 */
@Component
public class MetricasDePedido {

	public record PorPlano(String plano, long quantidade, long faturamentoCentavos) {}

	/** Segundos de {@code pago_em} a {@code enviada_em}; nulos sem nenhum enviado no periodo. */
	public record Latencia(Double p50Segundos, Double p90Segundos) {}

	public record Resumo(long criados, long pagos, long gratuitos, long enviados, long emErroAgora, long faturamentoCentavos,
			long ticketMedioCentavos, List<PorPlano> porPlano, Latencia latencia) {}

	private final JdbcClient jdbc;
	private final CatalogoApi catalogo;

	MetricasDePedido(JdbcClient jdbc, CatalogoApi catalogo) {
		this.jdbc = jdbc;
		this.catalogo = catalogo;
	}

	public Resumo resumo(Periodo p) {
		var ini = OffsetDateTime.ofInstant(p.inicio(), ZoneOffset.UTC);
		var fim = OffsetDateTime.ofInstant(p.fimExclusivo(), ZoneOffset.UTC);
		var c = jdbc.sql("""
				select
				  count(*) filter (where criado_em >= :ini and criado_em < :fim) criados,
				  count(*) filter (where pago_em >= :ini and pago_em < :fim and not gratuito) pagos,
				  count(*) filter (where pago_em >= :ini and pago_em < :fim and gratuito) gratuitos,
				  count(*) filter (where enviada_em >= :ini and enviada_em < :fim) enviados,
				  count(*) filter (where status = 'ERRO') erro,
				  coalesce(sum(valor_centavos) filter (where pago_em >= :ini and pago_em < :fim and not gratuito), 0) faturamento,
				  percentile_cont(0.5) within group (order by extract(epoch from enviada_em - pago_em))
				    filter (where enviada_em >= :ini and enviada_em < :fim and pago_em is not null) p50,
				  percentile_cont(0.9) within group (order by extract(epoch from enviada_em - pago_em))
				    filter (where enviada_em >= :ini and enviada_em < :fim and pago_em is not null) p90
				from pedido""").param("ini", ini).param("fim", fim).query((r, i) -> new Object[] { r.getLong("criados"),
						r.getLong("pagos"), r.getLong("gratuitos"), r.getLong("enviados"), r.getLong("erro"),
						r.getLong("faturamento"), r.getObject("p50"), r.getObject("p90") }).single();
		long pagos = (Long) c[1], faturamento = (Long) c[5];
		var linhas = jdbc.sql("""
				select plano_id, count(*) n, sum(valor_centavos) fat from pedido
				where pago_em >= :ini and pago_em < :fim and not gratuito group by plano_id order by plano_id""")
				.param("ini", ini).param("fim", fim)
				.query((r, i) -> new long[] { r.getLong("plano_id"), r.getLong("n"), r.getLong("fat") }).list();
		var nomes = catalogo.planos(linhas.stream().map(l -> l[0]).toList());
		var porPlano = new ArrayList<PorPlano>();
		for (var l : linhas) {
			var plano = nomes.get(l[0]);
			porPlano.add(new PorPlano(plano == null ? "Plano " + l[0] : plano.nome(), l[1], l[2]));
		}
		return new Resumo((Long) c[0], pagos, (Long) c[2], (Long) c[3], (Long) c[4], faturamento,
				pagos == 0 ? 0 : Math.round((double) faturamento / pagos), porPlano,
				new Latencia((Double) c[6], (Double) c[7]));
	}
}
