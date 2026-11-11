package me.clama.oracao.interno;

import java.util.Map;
import java.util.UUID;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.fila.Fila;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pagamento.PagamentoApi;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.PedidoResumo;
import me.clama.pedido.StatusPedido;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acoes do admin sobre o pedido e a oracao (D10): gratuidade, reenvio e regeneracao. Cada uma e uma transacao so, com a
 * auditoria dentro. O pedido muda pelo {@link PedidoApi} (que trava a linha e publica o evento); o {@link OuvinteDePedido}
 * enfileira ou antecipa o job na mesma transacao. {@code ENVIADA} nunca regride.
 */
@Component
public class AcoesDoAdminSobreOracao {

	static final String ENTIDADE = "pedido";

	/** {@code jaEmAndamento}: o reenvio nada fez porque a geracao ou o envio ainda esta na fila e vai entregar a oracao. */
	public record Resultado(PedidoResumo pedido, boolean jaEmAndamento) {}

	private final PedidoApi pedidos;
	private final PagamentoApi pagamento;
	private final Fila fila;
	private final JobGerarOracao gerar;
	private final JobEnviarOracao enviar;
	private final JobReenviarOracao reenviar;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;

	AcoesDoAdminSobreOracao(PedidoApi pedidos, PagamentoApi pagamento, Fila fila, JobGerarOracao gerar, JobEnviarOracao enviar,
			JobReenviarOracao reenviar, Auditoria auditoria, TransactionTemplate transacao) {
		this.pedidos = pedidos;
		this.pagamento = pagamento;
		this.fila = fila;
		this.gerar = gerar;
		this.enviar = enviar;
		this.reenviar = reenviar;
		this.auditoria = auditoria;
		this.transacao = transacao;
	}

	/**
	 * Cancela o Pix pendente e concede a gratuidade. O cancelamento chama o Mercado Pago com a linha do pedido travada: e o
	 * mesmo padrao da cobranca (4a), por isso na mesma transacao da concessao. Pagamento ja aprovado: 409
	 * {@code pagamento_ja_aprovado} e nada muda (rollback). Repasse nao se toca.
	 */
	public Resultado conceder(Long autorId, UUID id) {
		return transacao.execute(s -> {
			if (resumo(id).status() != StatusPedido.AGUARDANDO_PAGAMENTO) {
				throw conflito("gratuidade_indisponivel", "Só dá para conceder gratuidade enquanto o pedido aguarda o pagamento.");
			}
			pagamento.cancelarCobranca(id);
			pedidos.concederGratuidade(id);
			auditoria.registrar(autorId, ENTIDADE, id.toString(), Acao.MUDANCA, Map.of("acao", "gratuidade"));
			return new Resultado(resumo(id), false);
		});
	}

	/** Sempre envia de fato (chave nova). Com a geracao, o envio ou outro reenvio na fila, no-op: esse job ja entrega a oracao. */
	public Resultado reenviar(Long autorId, UUID id) {
		return transacao.execute(s -> {
			var d = pedidos.dadosParaOracao(id);
			if (d.oracao() == null || !(d.status() == StatusPedido.ORACAO_GERADA || d.status() == StatusPedido.ENVIADA
					|| d.status() == StatusPedido.ERRO)) {
				throw conflito("reenvio_indisponivel", "Esse pedido ainda não tem uma oração pronta para reenviar.");
			}
			if (fila.existePendente(gerar.getName(), gerar.instancia(id)) || fila.existePendente(enviar.getName(), enviar.instancia(id))
					|| fila.existePendente(reenviar.getName(), reenviar.instancia(id))) {
				return new Resultado(resumo(id), true);
			}
			pedidos.solicitarReenvio(id);
			auditoria.registrar(autorId, ENTIDADE, id.toString(), Acao.MUDANCA, Map.of("acao", "reenvio"));
			return new Resultado(resumo(id), false);
		});
	}

	/** So ERRO sem oracao: volta a PAGO, zera tentativas e erro; job de geracao ja na fila e antecipado (pelo ouvinte). */
	public Resultado regenerar(Long autorId, UUID id) {
		return transacao.execute(s -> {
			var d = pedidos.dadosParaOracao(id);
			if (d.status() != StatusPedido.ERRO || d.oracao() != null) {
				throw conflito("regeneracao_indisponivel", d.oracao() != null
						? "Essa oração já foi gerada; use o reenvio."
						: "Só dá para gerar de novo uma oração que falhou.");
			}
			pedidos.solicitarRegeneracao(id);
			auditoria.registrar(autorId, ENTIDADE, id.toString(), Acao.MUDANCA, Map.of("acao", "regeneracao"));
			return new Resultado(resumo(id), false);
		});
	}

	private PedidoResumo resumo(UUID id) {
		return pedidos.resumo(id).orElseThrow(PedidoNaoEncontradoException::new);
	}

	private static ErroDeNegocio conflito(String codigo, String pastoral) {
		return new ErroDeNegocio(HttpStatus.CONFLICT, codigo, pastoral, pastoral);
	}
}
