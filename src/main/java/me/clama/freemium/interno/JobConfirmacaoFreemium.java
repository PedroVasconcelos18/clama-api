package me.clama.freemium.interno;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import me.clama.infra.email.EnviadorDeEmail;
import me.clama.infra.email.ModelosDeEmail;
import me.clama.infra.fila.TipoDeJob;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code confirmacao-freemium/<pedido>} (D5, D12): se o pedido ainda aguarda a confirmacao, emite um link (so o hash
 * vai ao banco, em transacao curta) e manda o e-mail fora de transacao, com a chave {@code confirmacao-freemium/<hash>}.
 * Uma retentativa emite outro link e os anteriores seguem validos. Ao desistir so o log e o Sentry do {@link TipoDeJob}:
 * o pedido segue pendente ate a reconciliacao, sem alerta ao admin.
 */
@Component
@EnableConfigurationProperties(FreemiumProps.class)
class JobConfirmacaoFreemium extends TipoDeJob<UUID> {

	private static final Logger LOG = LoggerFactory.getLogger(JobConfirmacaoFreemium.class);

	private final PedidoApi pedidos;
	private final Confirmacoes confirmacoes;
	private final ModelosDeEmail modelos;
	private final EnviadorDeEmail enviador;
	private final FreemiumProps props;

	JobConfirmacaoFreemium(PedidoApi pedidos, Confirmacoes confirmacoes, ModelosDeEmail modelos, EnviadorDeEmail enviador,
			FreemiumProps props) {
		super("confirmacao-freemium", UUID.class, new Retentativas(3, props.esperaEmail(), 2.0));
		this.pedidos = pedidos;
		this.confirmacoes = confirmacoes;
		this.modelos = modelos;
		this.enviador = enviador;
		this.props = props;
	}

	@Override
	protected void executar(UUID id, int tentativa) {
		try {
			var d = pedidos.dadosParaOracao(id);
			if (d.status() != StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL) {
				LOG.info("confirmacao-freemium: pedido em {}, nada feito (pedido_id={})", d.status(), id);
				return;
			}
			var token = confirmacoes.emitir(id);
			var primeiro = d.nome() == null || d.nome().isBlank() ? "Amada" : d.nome().strip().split("\\s+")[0];
			var link = props.frontendUrl().replaceAll("/+$", "") + "/confirmar?token=" + token;
			var email = modelos.montar(d.email(), "Confirme sua oração — clama", "confirmacao_freemium",
					Map.of("primeiro_nome", primeiro, "link_confirmacao", link,
							"expira_em_horas", String.valueOf(props.validadeLink().toHours())));
			enviador.enviar(email, "confirmacao-freemium/" + Confirmacoes.hash(token));
			LOG.info("confirmacao-freemium: e-mail enviado (pedido_id={})", id);
		} catch (PedidoNaoEncontradoException e) {
			LOG.warn("confirmacao-freemium: pedido nao existe mais (pedido_id={})", id);
		}
	}
}
