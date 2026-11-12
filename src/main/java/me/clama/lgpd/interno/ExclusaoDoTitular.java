package me.clama.lgpd.interno;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.clama.blog.BlogApi;
import me.clama.conta.ContaApi;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pagamento.PagamentoApi;
import me.clama.pedido.PedidoApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exclusao do titular (D12), numa transacao so: refaz a busca e aborta se o conjunto mudou ou se ha pedido em
 * processamento; pedido com pagamento ou repasse e anonimizado, os demais sao apagados; o motivo do ban na auditoria e
 * redigido; as contas saem pela exclusao da conta (guardas, sessoes). Qualquer erro desfaz tudo. O bloqueio do freemium
 * fica (R22). O e-mail nunca vai para log nem auditoria.
 */
@Service
public class ExclusaoDoTitular {

	private static final Logger LOG = LoggerFactory.getLogger(ExclusaoDoTitular.class);

	public static final String PASTORAL_CONFIRMACAO = "Digite o e-mail exatamente como aparece para confirmar.";

	public record ResultadoDaExclusao(int pedidosApagados, int pedidosAnonimizados, int contasExcluidas) {}

	public record BlogApagado(long comentarios, long curtidas) {}

	private final BuscaDoTitular busca;
	private final ContaApi conta;
	private final PedidoApi pedido;
	private final PagamentoApi pagamento;
	private final BlogApi blog;
	private final Auditoria auditoria;

	ExclusaoDoTitular(BuscaDoTitular busca, ContaApi conta, PedidoApi pedido, PagamentoApi pagamento, BlogApi blog,
			Auditoria auditoria) {
		this.busca = busca;
		this.conta = conta;
		this.pedido = pedido;
		this.pagamento = pagamento;
		this.blog = blog;
		this.auditoria = auditoria;
	}

	/** 400 se a confirmacao nao e o e-mail (sem diferenciar caixa). */
	static void exigirConfirmacao(String email, String confirmacao) {
		if (confirmacao == null || !confirmacao.strip().equalsIgnoreCase(email.strip())) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", PASTORAL_CONFIRMACAO,
					List.of(new ErroDeCampo("confirmacao", PASTORAL_CONFIRMACAO)));
		}
	}

	@Transactional
	public ResultadoDaExclusao excluir(Long autorId, String email, String confirmacao, Collection<UUID> pedidoIds, Collection<Long> contaIds) {
		exigirConfirmacao(email, confirmacao);
		var achado = busca.achar(email);
		if (achado.vazio() && pedidoIds.isEmpty() && contaIds.isEmpty()) {
			throw new ErroDeNegocio(HttpStatus.NOT_FOUND, "titular_nao_encontrado", "Titular não encontrado.", Exportacao.NAO_ENCONTRADO);
		}
		if (!new HashSet<>(achado.pedidos()).equals(new HashSet<>(pedidoIds)) || !new HashSet<>(achado.contaIds()).equals(new HashSet<>(contaIds))) {
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "titular_mudou", "Os dados do titular mudaram desde a busca.",
					"Os dados desse e-mail mudaram desde a busca. Busque de novo antes de excluir.");
		}
		if (!pedido.emProcessamento(achado.pedidos()).isEmpty()) { // trava as linhas ate o commit
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "pedido_em_processamento", "Há pedido em processamento.",
					"Há pedido sendo processado agora. Tente de novo em alguns minutos.");
		}
		Set<UUID> anonimizar = pagamento.pedidosComPagamento(achado.pedidos());
		var apagar = achado.pedidos().stream().filter(id -> !anonimizar.contains(id)).toList();

		long comentarios = 0, curtidas = 0;
		for (var c : achado.contas()) {
			var n = blog.contagem(c.id());
			comentarios += n.comentarios();
			curtidas += n.curtidas();
			auditoria.redigir("banimento", String.valueOf(c.id()), "motivo");
			conta.excluir(autorId, c.id()); // guardas: 409 desfaz tudo
		}
		pedido.anonimizar(anonimizar);
		pedido.apagar(apagar);

		var r = new ResultadoDaExclusao(apagar.size(), anonimizar.size(), achado.contas().size());
		var diff = new LinkedHashMap<String, Object>();
		diff.put("pedidosApagados", r.pedidosApagados());
		diff.put("pedidosAnonimizados", r.pedidosAnonimizados());
		diff.put("contasExcluidas", r.contasExcluidas());
		diff.put("comentarios", comentarios);
		diff.put("curtidas", curtidas);
		diff.put("contaIds", achado.contaIds());
		diff.put("pedidoIds", achado.pedidos());
		auditoria.registrar(autorId, "lgpd", UUID.randomUUID().toString(), Acao.EXCLUSAO, diff);
		LOG.info("Exclusao LGPD (event=lgpd_exclusao, pedidos_apagados={}, pedidos_anonimizados={}, contas={})",
				r.pedidosApagados(), r.pedidosAnonimizados(), r.contasExcluidas());
		return r;
	}

	/** "Apagar so o blog" (D13): comentarios (com IPs) e curtidas; a conta, os pedidos e o ban ficam. */
	@Transactional
	public BlogApagado excluirDoBlog(Long autorId, String email, String confirmacao) {
		exigirConfirmacao(email, confirmacao);
		var c = conta.contaPorEmail(email).orElseThrow(() -> new ErroDeNegocio(HttpStatus.NOT_FOUND, "titular_nao_encontrado",
				"Titular não encontrado.", Exportacao.NAO_ENCONTRADO));
		var n = blog.apagarDoUsuario(c.id());
		var diff = new LinkedHashMap<String, Object>();
		diff.put("comentarios", n.comentarios());
		diff.put("curtidas", n.curtidas());
		diff.put("contaIds", List.of(c.id()));
		auditoria.registrar(autorId, "lgpd_blog", UUID.randomUUID().toString(), Acao.EXCLUSAO, diff);
		return new BlogApagado(n.comentarios(), n.curtidas());
	}
}
