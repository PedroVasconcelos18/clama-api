package me.clama.oracao.interno;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.infra.fila.Fila;
import me.clama.infra.fila.TipoDeJob;
import me.clama.pedido.DadosParaOracao;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.TransicaoInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code gerar-oracao/<pedido>} (INV:105, 108, 109). Tres passos: {@code iniciarGeracao} numa transacao curta (recusa
 * AGUARDANDO_PAGAMENTO, ERRO...: pula), a Claude <b>fora</b> de transacao, e {@code registrarOracao} + enfileirar
 * {@code enviar-oracao} numa transacao so. Idempotente: com a oracao ja gravada nao chama a Claude, so garante o envio.
 *
 * <ul>
 * <li>Creditos esgotados: ERRO {@code credit_balance} e alerta, sem retentar (so volta quando o admin recarregar).</li>
 * <li>Falha nao retentavel (pedido recusado, arquivo de contexto que sumiu na Files API): ERRO {@code falha_geracao} e
 * alerta.</li>
 * <li>Indisponivel, banco, o resto: a fila retenta (3 vezes, 60 s). Esgotado: AGUARDANDO_NOVA_TENTATIVA e de novo em
 * 300 s, ate 3 vezes; depois ERRO {@code falha_geracao} e alerta.</li>
 * </ul>
 */
@Component
class JobGerarOracao extends TipoDeJob<UUID> {

	private static final Logger LOG = LoggerFactory.getLogger(JobGerarOracao.class);
	static final int NOVAS_TENTATIVAS = 3;
	private static final EnumSet<StatusPedido> EM_ANDAMENTO = EnumSet.of(StatusPedido.PAGO, StatusPedido.GERANDO_ORACAO,
			StatusPedido.AGUARDANDO_NOVA_TENTATIVA);

	private final PedidoApi pedidos;
	private final CatalogoApi catalogo;
	private final PromptVigente prompts;
	private final AdminDeDocumentos documentos;
	private final ClienteClaude claude;
	private final Fila fila;
	private final JobEnviarOracao enviar;
	private final AlertasDaOracao alertas;
	private final TransactionTemplate tx;
	private final Duration novaTentativa;

	JobGerarOracao(PedidoApi pedidos, CatalogoApi catalogo, PromptVigente prompts, AdminDeDocumentos documentos,
			ClienteClaude claude, @Lazy Fila fila, JobEnviarOracao enviar, AlertasDaOracao alertas, TransactionTemplate tx,
			@Value("${clama.oracao.espera-geracao:60s}") Duration espera,
			@Value("${clama.oracao.espera-nova-tentativa:300s}") Duration novaTentativa) {
		super("gerar-oracao", UUID.class, new Retentativas(3, espera, 1.0));
		this.pedidos = pedidos;
		this.catalogo = catalogo;
		this.prompts = prompts;
		this.documentos = documentos;
		this.claude = claude;
		this.fila = fila;
		this.enviar = enviar;
		this.alertas = alertas;
		this.tx = tx;
		this.novaTentativa = novaTentativa;
	}

	@Override
	protected void executar(UUID id, int tentativa) {
		var d = iniciar(id);
		if (d == null) return;
		var plano = catalogo.planos(List.of(d.planoId())).get(d.planoId());
		if (plano == null) throw new IllegalStateException("plano " + d.planoId() + " do pedido nao existe");
		var prompt = prompts.vigente().prompt();
		var fileIds = documentos.fileIdsAtivos();
		LOG.info("gerar-oracao: chamando a Claude (pedido_id={}, tentativa={}, novas_tentativas={}, documentos={})", id,
				tentativa + 1, d.tentativas(), fileIds.size());
		String texto;
		try {
			texto = claude.gerar(prompt.sistema(), MontagemDoPrompt.mensagem(prompt, d, plano), fileIds);
		} catch (ClienteClaude.CreditosEsgotados e) {
			LOG.error("gerar-oracao: Anthropic sem creditos (pedido_id={})", id);
			Sentry.captureMessage("Anthropic sem créditos: pedido em ERRO aguardando o admin", SentryLevel.ERROR);
			falhar(id, "credit_balance", "A conta da Anthropic está sem créditos. Recarregue e regenere o pedido pelo admin.");
			return;
		} catch (ClienteClaude.Indisponivel e) {
			LOG.warn("gerar-oracao: Claude indisponivel, a fila retenta (pedido_id={}, tentativa={})", id, tentativa + 1);
			throw e;
		} catch (ClienteClaude.Falha e) {
			LOG.error("gerar-oracao: Claude recusou o pedido (pedido_id={}, documentos={})", id, fileIds.size(), e);
			falhar(id, "falha_geracao", "A Claude recusou o pedido de geração (sem retentativa)."
					+ (fileIds.isEmpty() ? "" : " Há documentos de contexto ativos: conferir documentos ativos (um arquivo pode ter sumido da Files API)."));
			return;
		}
		tx.executeWithoutResult(s -> {
			pedidos.registrarOracao(id, texto);
			fila.enfileirar(enviar, enviar.instancia(id), id, Instant.now());
		});
		LOG.info("gerar-oracao: oracao gravada, envio enfileirado (pedido_id={})", id);
	}

	/**
	 * Os dados para gerar, ja em GERANDO_ORACAO; nulo = nada a fazer. Sem transacao envolvendo: cada chamada ao
	 * {@link PedidoApi} tem a sua, e a transicao recusada nao deixa uma transacao externa marcada para rollback.
	 */
	private DadosParaOracao iniciar(UUID id) {
		DadosParaOracao d;
		try {
			d = pedidos.dadosParaOracao(id);
		} catch (PedidoNaoEncontradoException e) {
			LOG.warn("gerar-oracao: pedido nao existe mais (pedido_id={})", id);
			return null;
		}
		if (d.status() == StatusPedido.ORACAO_GERADA) {
			tx.executeWithoutResult(s -> fila.enfileirar(enviar, enviar.instancia(id), id, Instant.now())); // no-op se ja esta na fila
			LOG.info("gerar-oracao: oracao ja gerada, so garante o envio (pedido_id={})", id);
			return null;
		}
		try {
			pedidos.iniciarGeracao(id);
		} catch (TransicaoInvalidaException e) {
			LOG.info("gerar-oracao: pedido em {}, nada a gerar (pedido_id={})", d.status(), id);
			return null;
		}
		return d;
	}

	/** O pedido vai a ERRO (transacao propria) e, depois do commit, o alerta. */
	private void falhar(UUID id, String motivo, String detalhe) {
		try {
			pedidos.falhar(id, motivo);
		} catch (TransicaoInvalidaException e) {
			LOG.warn("gerar-oracao: pedido ja mudou de status, sem {} (pedido_id={})", motivo, id);
			return;
		}
		alertas.alertar(id, motivo, detalhe);
	}

	/** INV:109: reagenda em {@code novaTentativa} ate {@link #NOVAS_TENTATIVAS} vezes; depois ERRO e alerta. */
	@Override
	protected void aoDesistir(UUID id, Throwable erro) {
		DadosParaOracao d;
		try {
			d = pedidos.dadosParaOracao(id);
		} catch (PedidoNaoEncontradoException e) {
			return;
		}
		if (d.status() == StatusPedido.GERANDO_ORACAO && d.tentativas() < NOVAS_TENTATIVAS) {
			tx.executeWithoutResult(s -> {
				pedidos.agendarNovaTentativa(id, "falha_geracao");
				fila.enfileirar(this, instancia(id), id, Instant.now().plus(novaTentativa));
			});
			LOG.warn("gerar-oracao: retentativas esgotadas, nova tentativa em {} s (pedido_id={}, novas_tentativas={})",
					novaTentativa.toSeconds(), id, d.tentativas() + 1);
		} else if (EM_ANDAMENTO.contains(d.status())) {
			LOG.error("gerar-oracao: desistiu de vez (pedido_id={}, novas_tentativas={})", id, d.tentativas());
			falhar(id, "falha_geracao", "A geração falhou em todas as tentativas (Claude indisponível ou erro interno).");
		}
	}
}
