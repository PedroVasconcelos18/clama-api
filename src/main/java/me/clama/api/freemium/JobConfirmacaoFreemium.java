package me.clama.api.freemium;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.email.EnviadorDeEmail;
import me.clama.api.comum.email.ResultadoEnvio;
import me.clama.api.comum.email.modelo.ModelosDeEmail;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.oracao.AlertaDeErro;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * enviar_email_confirmacao_freemium_task (notifications/tasks.py:283-377). O payload leva so o pedido (decisao R11):
 * o token e buscado pelo pedido. Le numa transacao, envia fora dela com a chave {@code confirmacao-freemium/<id>} (a
 * retentativa reenvia a mesma chave). Token inexistente, usado ou vencido: erro + alerta, sem e-mail. Esgotado
 * (30/60/120 s): so log; o status nao muda (o Sentry vem do observador da fila), como no Django.
 */
@Component
public class JobConfirmacaoFreemium implements TipoDeJob<UUID> {
    private static final Logger LOG = LoggerFactory.getLogger(JobConfirmacaoFreemium.class);
    public static final String NOME = "confirmacao-freemium";

    private final PedidoRepository pedidos;
    private final TokenDeConfirmacaoRepository tokens;
    private final EnviadorDeEmail enviador;
    private final ModelosDeEmail modelos;
    private final AlertaDeErro alerta;
    private final TransactionTemplate tx;
    private final Clock relogio;

    JobConfirmacaoFreemium(PedidoRepository pedidos, TokenDeConfirmacaoRepository tokens, EnviadorDeEmail enviador,
            ModelosDeEmail modelos, AlertaDeErro alerta, TransactionTemplate tx, Clock relogio) {
        this.pedidos = pedidos;
        this.tokens = tokens;
        this.enviador = enviador;
        this.modelos = modelos;
        this.alerta = alerta;
        this.tx = tx;
        this.relogio = relogio;
    }

    public static String instancia(UUID pedidoId) {
        return NOME + "/" + pedidoId;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public Class<UUID> payload() {
        return UUID.class;
    }

    @Override
    public Duration esperaEntreTentativas() {
        return Duration.ofSeconds(30);
    }

    @Override
    public double fatorDeBackoff() {
        return 2.0;
    }

    @Override
    public void executar(UUID id, int retentativa) {
        Email email = tx.execute(s -> preparar(id));
        if (email == null) return;
        if (enviador.enviar(email) == ResultadoEnvio.JA_ENVIADO) {
            LOG.warn("E-mail ja enviado antes (idempotencia da Resend) (event=email_already_sent, pedido_id={})", id);
        }
        LOG.info("Email de confirmacao freemium enviado (event=email_confirmacao_freemium_sent, pedido_id={})", id);
    }

    private Email preparar(UUID id) {
        Pedido p = pedidos.findById(id).orElse(null);
        if (p == null) {
            LOG.warn("enviar_email_confirmacao_freemium_pedido_nao_encontrado (event=freemium_confirmacao_pedido_not_found,"
                    + " pedido_id={})", id);
            return null;
        }
        if (p.getStatus() != StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL) {
            LOG.info("enviar_email_confirmacao_freemium_status_mudou (event=freemium_confirmacao_status_skipped,"
                    + " pedido_id={}, current_status={})", id, p.getStatus().valor());
            return null;
        }
        Optional<TokenDeConfirmacao> t = tokens.doPedido(id);
        OffsetDateTime agora = OffsetDateTime.now(relogio);
        if (t.isEmpty() || !t.get().getExpiresAt().isAfter(agora) || t.get().getUsedAt() != null) {
            LOG.warn("Token expirado ou consumido antes do email de confirmacao ser enviado"
                    + " (event=freemium_confirmacao_token_invalido_pre_envio, pedido_id={}, token_existe={})", id,
                    t.isPresent());
            p.setStatus(StatusPedido.ERRO);
            p.setLastError("token_expirado_antes_de_envio");
            alerta.registrar(id);
            Sentry.captureMessage("Freemium: token expirou antes do email de confirmação para pedido " + id,
                    SentryLevel.WARNING);
            return null;
        }
        return modelos.confirmacaoFreemium(p.getEmail(), p.getNome(),
                modelos.frontendBase() + "/confirmar?token=" + t.get().getToken()).comChave(instancia(id));
    }

    /** Retentativas esgotadas: so log (o Django so manda o erro ao Sentry); o pedido segue aguardando. */
    @Override
    public void esgotou(UUID id, Throwable erro) {
        LOG.error("enviar_email_confirmacao_freemium_falha_persistente (event=freemium_confirmacao_email_failed,"
                + " pedido_id={})", id);
    }
}
