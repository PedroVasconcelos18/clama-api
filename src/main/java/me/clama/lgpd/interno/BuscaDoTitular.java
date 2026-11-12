package me.clama.lgpd.interno;

import java.util.List;
import java.util.UUID;
import me.clama.blog.BlogApi;
import me.clama.conta.ContaApi;
import me.clama.conta.UsuarioResumo;
import me.clama.freemium.FreemiumApi;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.pagamento.PagamentoApi;
import me.clama.pedido.PedidoApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A busca do titular pelo e-mail (D10): a conta pelo e-mail canonico; os pedidos pelo {@code email_hmac} (variantes do
 * Gmail e doacao anonima sem conta incluidas) ou pelo dono, nas contas achadas. Sem varredura e sem SQL aqui.
 */
@Service
public class BuscaDoTitular {

	/** O que existe do titular. {@code contas} tem no maximo uma (o canonico e unico). */
	public record Achado(List<UsuarioResumo> contas, List<UUID> pedidos) {
		public boolean vazio() { return contas.isEmpty() && pedidos.isEmpty(); }

		public List<Long> contaIds() { return contas.stream().map(UsuarioResumo::id).toList(); }
	}

	public record ContaDaPrevia(Long id, String papel) {}

	public record PedidosDaPrevia(int total, int aApagar, int aAnonimizar, int emProcessamento) {}

	public record BlogDaPrevia(long comentarios, long curtidas, boolean banido) {}

	/** A previa: contagens e os ids do que a exclusao atingiria (a exclusao exige os mesmos ids de volta). */
	public record PreviaDoTitular(List<ContaDaPrevia> contas, PedidosDaPrevia pedidos, BlogDaPrevia blog, boolean freemiumBloqueado,
			List<UUID> pedidoIds, List<Long> contaIds) {}

	private final ContaApi conta;
	private final PedidoApi pedido;
	private final PagamentoApi pagamento;
	private final BlogApi blog;
	private final FreemiumApi freemium;
	private final HmacDeIdentificador hmac;

	BuscaDoTitular(ContaApi conta, PedidoApi pedido, PagamentoApi pagamento, BlogApi blog, FreemiumApi freemium,
			HmacDeIdentificador hmac) {
		this.conta = conta;
		this.pedido = pedido;
		this.pagamento = pagamento;
		this.blog = blog;
		this.freemium = freemium;
		this.hmac = hmac;
	}

	/** Na transacao de quem chama (a exclusao refaz a busca dentro da dela). */
	public Achado achar(String email) {
		var contas = conta.contaPorEmail(email).map(List::of).orElse(List.<UsuarioResumo>of());
		var pedidos = pedido.doTitular(email, contas.stream().map(UsuarioResumo::id).toList());
		return new Achado(contas, pedidos);
	}

	/** Transacao de escrita (nao readOnly): {@code emProcessamento} trava as linhas com FOR UPDATE, e o Postgres recusa isso em readOnly. */
	@Transactional
	public PreviaDoTitular previa(String email) {
		var achado = achar(email);
		var comPagamento = pagamento.pedidosComPagamento(achado.pedidos());
		var processando = pedido.emProcessamento(achado.pedidos()).size();
		long comentarios = 0, curtidas = 0;
		boolean banido = false;
		for (var c : achado.contas()) {
			var n = blog.contagem(c.id());
			comentarios += n.comentarios();
			curtidas += n.curtidas();
			banido |= n.banido();
		}
		var bloqueado = freemium.bloqueado(hmac.email(email), pedido.cpfHmacs(achado.pedidos()));
		int total = achado.pedidos().size();
		return new PreviaDoTitular(achado.contas().stream().map(c -> new ContaDaPrevia(c.id(), c.papel())).toList(),
				new PedidosDaPrevia(total, total - comPagamento.size(), comPagamento.size(), processando),
				new BlogDaPrevia(comentarios, curtidas, banido), bloqueado, achado.pedidos(), achado.contaIds());
	}
}
