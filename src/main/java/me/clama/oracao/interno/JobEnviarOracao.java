package me.clama.oracao.interno;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import me.clama.infra.email.Email;
import me.clama.infra.email.EnviadorDeEmail;
import me.clama.infra.email.ModelosDeEmail;
import me.clama.infra.fila.TipoDeJob;
import me.clama.pedido.DadosParaOracao;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.TransicaoInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code enviar-oracao/<pedido>} (INV:110, D12): e-mail {@code oracao} com a chave {@code oracao/<id>}, fora de transacao,
 * e depois {@code marcarEnviado} na transacao propria do {@link PedidoApi}. Marcacao que falha retenta com a mesma chave:
 * a Resend responde "ja enviado". Ja ENVIADA = no-op. Retentativas 30/60/120 s; esgotado = ERRO {@code falha_envio} e
 * alerta. Tambem faz o envio do {@link JobReenviarOracao}.
 */
@Component
class JobEnviarOracao extends TipoDeJob<UUID> {

	private static final Logger LOG = LoggerFactory.getLogger(JobEnviarOracao.class);

	private final PedidoApi pedidos;
	private final ModelosDeEmail modelos;
	private final EnviadorDeEmail enviador;
	private final JobAlertaAdmin alerta;
	private final TransactionTemplate tx;

	JobEnviarOracao(PedidoApi pedidos, ModelosDeEmail modelos, EnviadorDeEmail enviador, JobAlertaAdmin alerta, TransactionTemplate tx,
			@Value("${clama.oracao.espera-envio:30s}") Duration espera) {
		super("enviar-oracao", UUID.class, politica(espera));
		this.pedidos = pedidos;
		this.modelos = modelos;
		this.enviador = enviador;
		this.alerta = alerta;
		this.tx = tx;
	}

	static Retentativas politica(Duration espera) {
		return new Retentativas(3, espera, 2.0);
	}

	@Override
	protected void executar(UUID id, int tentativa) {
		enviar(id, "oracao/" + id, false);
	}

	@Override
	protected void aoDesistir(UUID id, Throwable erro) {
		desistiu(id);
	}

	/** {@code reenvio}: o pedido pode ja estar ENVIADA e o e-mail sai mesmo assim (a chave e outra). */
	void enviar(UUID id, String chave, boolean reenvio) {
		DadosParaOracao d;
		try {
			d = pedidos.dadosParaOracao(id);
		} catch (PedidoNaoEncontradoException e) {
			LOG.warn("enviar-oracao: pedido nao existe mais (pedido_id={})", id);
			return;
		}
		if (d.status() == StatusPedido.ENVIADA && !reenvio) {
			LOG.info("enviar-oracao: ja enviada (pedido_id={})", id);
			return;
		}
		if (d.oracao() == null || (d.status() != StatusPedido.ORACAO_GERADA && d.status() != StatusPedido.ENVIADA)) {
			LOG.warn("enviar-oracao: pedido em {} sem oracao para enviar, nada feito (pedido_id={})", d.status(), id);
			return;
		}
		enviador.enviar(email(d), chave);
		try {
			pedidos.marcarEnviado(id);
		} catch (TransicaoInvalidaException e) {
			LOG.warn("enviar-oracao: e-mail saiu, mas o pedido mudou para outro status (pedido_id={})", id);
			return;
		}
		LOG.info("enviar-oracao: enviada (pedido_id={}, reenvio={})", id, reenvio);
	}

	/**
	 * Retentativas esgotadas. Em ENVIADA (reenvio do admin) o status nao regride: so alerta. Senao ERRO {@code falha_envio}
	 * e alerta; a oracao fica para o reenvio.
	 */
	void desistiu(UUID id) {
		DadosParaOracao d;
		try {
			d = pedidos.dadosParaOracao(id);
		} catch (PedidoNaoEncontradoException e) {
			return;
		}
		if (d.status() == StatusPedido.ENVIADA) {
			tx.executeWithoutResult(s -> alerta.enfileirar(id, "falha_reenvio"));
			return;
		}
		try { // ERRO e alerta na mesma transacao; a transicao recusada e tratada fora do template
			tx.executeWithoutResult(s -> {
				pedidos.falhar(id, "falha_envio");
				alerta.enfileirar(id, "falha_envio");
			});
		} catch (TransicaoInvalidaException e) {
			LOG.warn("enviar-oracao: desistiu, mas o pedido ja esta em {} (pedido_id={})", d.status(), id);
		}
	}

	private Email email(DadosParaOracao d) {
		var primeiro = primeiroNome(d.nome());
		return modelos.montar(d.email(), "Sua oração chegou, " + primeiro, "oracao",
				Map.of("primeiro_nome", primeiro, "oracao", d.oracao(), "whatsapp_share_url", whatsapp(d.oracao())));
	}

	/** Primeira palavra do nome; "Amada" se vier vazio. */
	static String primeiroNome(String nome) {
		var limpo = nome == null ? "" : nome.strip();
		return limpo.isEmpty() ? "Amada" : limpo.split("\\s+")[0];
	}

	private static String whatsapp(String oracao) {
		var mensagem = "Recebi essa oração através do Clama:\n\n\"" + oracao + "\"\n\nFaça também seu pedido: https://clama.me";
		return "https://wa.me/?text=" + URLEncoder.encode(mensagem, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
