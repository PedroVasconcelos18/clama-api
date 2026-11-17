package me.clama.oracao.interno;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;
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
	private final PromptVigente prompts;
	private final AdminDeDocumentos documentos;
	private final ClienteClaude claude;
	private final Geracoes geracoes;
	private final Fila fila;
	private final JobEnviarOracao enviar;
	private final JobAlertaAdmin alerta;
	private final TransactionTemplate tx;
	private final Clock relogio;
	private final Duration novaTentativa;

	JobGerarOracao(PedidoApi pedidos, PromptVigente prompts, AdminDeDocumentos documentos,
			ClienteClaude claude, Geracoes geracoes, @Lazy Fila fila, JobEnviarOracao enviar, JobAlertaAdmin alerta, TransactionTemplate tx, Clock relogio,
			@Value("${clama.oracao.espera-geracao:60s}") Duration espera,
			@Value("${clama.oracao.espera-nova-tentativa:300s}") Duration novaTentativa) {
		super("gerar-oracao", UUID.class, new Retentativas(3, espera, 1.0));
		this.pedidos = pedidos;
		this.prompts = prompts;
		this.documentos = documentos;
		this.claude = claude;
		this.geracoes = geracoes;
		this.fila = fila;
		this.enviar = enviar;
		this.alerta = alerta;
		this.tx = tx;
		this.relogio = relogio;
		this.novaTentativa = novaTentativa;
	}

	@Override
	protected void executar(UUID id, int tentativa) {
		var d = iniciar(id);
		if (d == null) return;
		var prompt = prompts.vigente().prompt();
		var fileIds = documentos.fileIdsAtivos();
		LOG.info("gerar-oracao: chamando a Claude (pedido_id={}, tentativa={}, novas_tentativas={}, documentos={})", id,
				tentativa + 1, d.tentativas(), fileIds.size());
		ClienteClaude.Resposta resposta;
		try {
			resposta = claude.gerar(prompt.sistema(), MontagemDoPrompt.mensagem(prompt, d), fileIds);
		} catch (ClienteClaude.CreditosEsgotados e) {
			LOG.error("gerar-oracao: Anthropic sem creditos (pedido_id={})", id);
			Sentry.captureMessage("Anthropic sem créditos: pedido em ERRO aguardando o admin", SentryLevel.ERROR);
			falhar(id, "credit_balance");
			return;
		} catch (ClienteClaude.Indisponivel e) {
			LOG.warn("gerar-oracao: Claude indisponivel, a fila retenta (pedido_id={}, tentativa={})", id, tentativa + 1);
			throw e;
		} catch (ClienteClaude.Falha e) {
			if (!documentos.fileIdsAtivos().equals(fileIds)) { // o admin trocou ou apagou um documento no meio: a fila retenta com os novos
				LOG.warn("gerar-oracao: documentos ativos mudaram durante a chamada, a fila retenta (pedido_id={})", id);
				throw new IllegalStateException("documentos de contexto mudaram durante a geracao", e);
			}
			LOG.error("gerar-oracao: Claude recusou o pedido (pedido_id={}, documentos={})", id, fileIds.size(), e);
			falhar(id, "falha_geracao");
			return;
		}
		tx.executeWithoutResult(s -> {
			pedidos.registrarOracao(id, resposta.texto());
			geracoes.registrar(id, resposta.uso(), relogio.instant()); // mesma transacao: sem oracao, sem linha
			fila.enfileirar(enviar, enviar.instancia(id), id, relogio.instant());
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
			tx.executeWithoutResult(s -> fila.enfileirar(enviar, enviar.instancia(id), id, relogio.instant())); // no-op se ja esta na fila
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

	/**
	 * ERRO e o alerta na mesma transacao. A transicao recusada sai do template (que desfaz tudo) e e tratada aqui fora:
	 * pega-la la dentro deixaria a transacao marcada para rollback.
	 */
	private void falhar(UUID id, String motivo) {
		try {
			tx.executeWithoutResult(s -> {
				pedidos.falhar(id, motivo);
				alerta.enfileirar(id, motivo);
			});
		} catch (TransicaoInvalidaException e) {
			LOG.warn("gerar-oracao: pedido ja mudou de status, sem {} (pedido_id={})", motivo, id);
		}
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
				fila.enfileirar(this, instancia(id), id, relogio.instant().plus(novaTentativa));
			});
			LOG.warn("gerar-oracao: retentativas esgotadas, nova tentativa em {} s (pedido_id={}, novas_tentativas={})",
					novaTentativa.toSeconds(), id, d.tentativas() + 1);
		} else if (EM_ANDAMENTO.contains(d.status())) {
			LOG.error("gerar-oracao: desistiu de vez (pedido_id={}, novas_tentativas={})", id, d.tentativas());
			falhar(id, "falha_geracao");
		}
	}
}
