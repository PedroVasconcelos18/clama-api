package me.clama.pagamento.interno.relatorio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.catalogo.InstituicaoResumo;
import me.clama.pagamento.interno.RepasseProps;
import me.clama.pagamento.interno.Repasses;
import me.clama.pedido.PedidoApi;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Conciliacao do ledger (D7, INV:159): acha o que o relatorio, que so percorre {@code repasse}, nao consegue ver. So leitura,
 * quatro consultas. Os detalhes trazem o id do pedido e valores, nunca dado de doador. Erro tecnico aqui e excecao, nunca um
 * achado "critico" (o alarme falso do script antigo): quem chama decide como mostrar "a auditoria nao rodou".
 */
@Component
public class AuditoriaDoLedger {

	public enum Gravidade { CRITICO, ATENCAO }

	public enum Tipo {
		ORFAO(Gravidade.CRITICO), PEDIDO_NAO_PAGO(Gravidade.CRITICO), CALCULO_ERRADO(Gravidade.CRITICO), DIVERGENCIA_DE_BASE(Gravidade.ATENCAO);

		final Gravidade gravidade;

		Tipo(Gravidade gravidade) { this.gravidade = gravidade; }

		/** {@code orfao}, {@code pedido_nao_pago}... como o Django. */
		public String codigo() { return name().toLowerCase(java.util.Locale.ROOT); }
	}

	public record Achado(Gravidade gravidade, Tipo tipo, String instituicao, UUID pedidoId, String detalhe, long valorEmJogoCentavos) {}

	public record Resultado(List<Achado> achados) {
		public int criticos() { return (int) achados.stream().filter(a -> a.gravidade() == Gravidade.CRITICO).count(); }
		public long emJogoCentavos() {
			return achados.stream().filter(a -> a.gravidade() == Gravidade.CRITICO).mapToLong(Achado::valorEmJogoCentavos).sum();
		}
	}

	private record Linha(UUID pedidoId, long instituicaoId, long base, int percentual, long valor, String status) {}

	private static final String LEDGER = "select pedido_id, instituicao_id, valor_base_centavos, percentual, valor_centavos, status from repasse";

	private final JdbcClient jdbc;
	private final PedidoApi pedidos;
	private final CatalogoApi catalogo;
	private final RepasseProps props;

	AuditoriaDoLedger(JdbcClient jdbc, PedidoApi pedidos, CatalogoApi catalogo, RepasseProps props) {
		this.jdbc = jdbc;
		this.pedidos = pedidos;
		this.catalogo = catalogo;
		this.props = props;
	}

	/** Todas as instituicoes. Criticos primeiro, depois por tipo e pedido (ordem estavel). */
	public Resultado auditar() {
		var achados = new ArrayList<Achado>();

		// 1) orfao: cobranca paga, pedido com instituicao e nao gratuito cujo repasse daria > 0, sem linha de repasse
		var semRepasse = jdbc.sql("select pedido_id from cobranca c where paga_em is not null and not exists (select 1 from repasse r where r.pedido_id = c.pedido_id)")
				.query(UUID.class).list();
		var resumosDosPagos = pedidos.resumos(semRepasse);

		// 2) pedido_nao_pago: repasse vivo (REGISTRADO ou TRANSFERIDO) sem cobranca paga
		var semPagamento = jdbc.sql(LEDGER + " r where r.status <> 'CANCELADO' and not exists (select 1 from cobranca c where c.pedido_id = r.pedido_id and c.paga_em is not null)")
				.query((rs, i) -> linha(rs)).list();

		// 3 e 4) calculo_errado e divergencia_de_base olham todo o ledger
		var ledger = jdbc.sql(LEDGER).query((rs, i) -> linha(rs)).list();
		var resumosDoLedger = pedidos.resumos(ledger.stream().map(Linha::pedidoId).toList());

		var ids = new ArrayList<Long>();
		resumosDosPagos.values().forEach(p -> { if (p.instituicaoId() != null) ids.add(p.instituicaoId()); });
		ledger.forEach(l -> ids.add(l.instituicaoId()));
		var nomes = catalogo.instituicoes(ids.stream().distinct().toList());

		for (var p : resumosDosPagos.values()) {
			if (p.instituicaoId() == null || p.gratuito()) continue;
			var devido = Repasses.valor(p.valorCentavos(), props.percentual());
			if (devido <= 0) continue;
			achados.add(achado(Tipo.ORFAO, nomes, p.instituicaoId(), p.id(),
					"pedido pago de " + p.valorCentavos() + " centavos sem linha no ledger (repasse devido: " + devido + " centavos)", devido));
		}
		for (var l : semPagamento) {
			achados.add(achado(Tipo.PEDIDO_NAO_PAGO, nomes, l.instituicaoId(), l.pedidoId(),
					"repasse " + l.status() + " mas o pedido não tem pagamento confirmado", l.valor()));
		}
		for (var l : ledger) {
			var esperado = Repasses.valor(l.base(), l.percentual());
			if (l.valor() != esperado) {
				achados.add(achado(Tipo.CALCULO_ERRADO, nomes, l.instituicaoId(), l.pedidoId(),
						"ledger diz " + l.valor() + " centavos; " + l.percentual() + "% de " + l.base() + " dá " + esperado, Math.abs(l.valor() - esperado)));
			}
			var p = resumosDoLedger.get(l.pedidoId());
			if (p != null && p.valorCentavos() != l.base()) {
				achados.add(achado(Tipo.DIVERGENCIA_DE_BASE, nomes, l.instituicaoId(), l.pedidoId(),
						"o ledger registrou " + l.base() + " centavos; o pedido hoje vale " + p.valorCentavos(), 0));
			}
		}
		achados.sort(Comparator.comparing((Achado a) -> a.gravidade()).thenComparing(Achado::tipo).thenComparing(a -> a.pedidoId().toString()));
		return new Resultado(List.copyOf(achados));
	}

	private static Achado achado(Tipo tipo, Map<Long, InstituicaoResumo> nomes, Long instituicaoId, UUID pedidoId, String detalhe, long valor) {
		var i = nomes.get(instituicaoId);
		return new Achado(tipo.gravidade, tipo, i == null ? "(instituição #" + instituicaoId + ")" : i.nome(), pedidoId, detalhe, valor);
	}

	private static Linha linha(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Linha(rs.getObject("pedido_id", UUID.class), rs.getLong("instituicao_id"), rs.getLong("valor_base_centavos"),
				rs.getInt("percentual"), rs.getLong("valor_centavos"), rs.getString("status"));
	}
}
