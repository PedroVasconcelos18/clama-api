package me.clama.api.oracao;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.email.EnviadorDeEmail;
import me.clama.api.comum.email.ResultadoEnvio;
import me.clama.api.comum.email.modelo.ModelosDeEmail;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.identidade.Usuario;
import me.clama.api.pedido.CanalEntrega;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * enviar_oracao_task (notifications/tasks.py:46-248), so por e-mail (WhatsApp/Z-API fora de escopo: vai por e-mail
 * tambem). Le o pedido numa transacao, envia fora dela com a chave de idempotencia ({@code oracao/<id>} ou
 * {@code oracao-freemium/<id>}) e marca enviada numa transacao propria: se a marcacao falha, a retentativa reenvia a
 * mesma chave e a Resend responde "ja enviado". Esgotado (30/60/120 s): erro e alerta (o pretendido do Django).
 */
@Component
public class JobEnviarOracao implements TipoDeJob<UUID> {
    private static final Logger LOG = LoggerFactory.getLogger(JobEnviarOracao.class);
    public static final String NOME = "enviar-oracao";
    public static final String PREFIXO_SENHA_FREEMIUM = "freemium:temp_password:";

    private final PedidoRepository pedidos;
    private final EnviadorDeEmail enviador;
    private final ModelosDeEmail modelos;
    private final SenhasTemporarias senhas;
    private final AlertaDeErro alerta;
    private final TransactionTemplate tx;
    private final Duration espera;

    JobEnviarOracao(PedidoRepository pedidos, EnviadorDeEmail enviador, ModelosDeEmail modelos,
            SenhasTemporarias senhas, AlertaDeErro alerta, TransactionTemplate tx,
            @Value("${clama.oracao.enviar-espera:30s}") Duration espera) {
        this.pedidos = pedidos;
        this.enviador = enviador;
        this.modelos = modelos;
        this.senhas = senhas;
        this.alerta = alerta;
        this.tx = tx;
        this.espera = espera;
    }

    public static String instancia(UUID id) {
        return NOME + "/" + id;
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
        return espera;
    }

    @Override
    public double fatorDeBackoff() {
        return 2.0;
    }

    /** email nulo = freemium ja enviado (so marca enviada). chaveSenha: apagar do cache depois de marcar. */
    private record Envio(Email email, boolean freemium, String chaveSenha) {}

    @Override
    public void executar(UUID id, int retentativa) {
        enviar(id, null, retentativa);
    }

    /**
     * Preparar, enviar e marcar, dividido com o {@link JobReenviarOracao}. {@code reenvio} nulo = o envio do pipeline;
     * senao, o reenvio do admin: chave {@code <chave>/reenvio/<reenvio>} e o freemium envia mesmo com
     * {@code oracao_email_sent_at} preenchido.
     */
    void enviar(UUID id, UUID reenvio, int retentativa) {
        Envio envio = tx.execute(s -> preparar(id, reenvio));
        if (envio == null) return;
        if (envio.email() != null && enviador.enviar(envio.email()) == ResultadoEnvio.JA_ENVIADO) {
            LOG.warn("E-mail ja enviado antes (idempotencia da Resend) (event=email_already_sent, pedido_id={})", id);
        }
        tx.executeWithoutResult(s -> {
            Pedido p = pedidos.findById(id).orElseThrow();
            if (envio.freemium() && envio.email() != null) {
                p.setOracaoEmailSentAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
            }
            p.setStatus(StatusPedido.ENVIADA);
        });
        if (envio.chaveSenha() != null) senhas.apagar(envio.chaveSenha());
        LOG.info("enviar_oracao_concluido (event=enviar_oracao_completed, pedido_id={}, attempt={}, reenvio={})", id,
                retentativa + 1, reenvio != null);
    }

    private Envio preparar(UUID id, UUID reenvio) {
        Pedido p = pedidos.findById(id).orElse(null);
        if (p == null) {
            LOG.warn("enviar_oracao_pedido_nao_encontrado (pedido_id={})", id);
            return null;
        }
        if (p.getStatus() == StatusPedido.ENVIADA) {
            LOG.info("enviar_oracao_ja_enviada (event=enviar_oracao_skipped, pedido_id={})", id);
            return null;
        }
        if (p.getOracaoGerada() == null || p.getOracaoGerada().isEmpty()) {
            LOG.error("enviar_oracao_sem_oracao (event=enviar_oracao_no_prayer, pedido_id={})", id);
            p.setStatus(StatusPedido.ERRO);
            alerta.registrar(id);
            return null;
        }
        if (p.getCanalEntrega() != CanalEntrega.EMAIL) {
            LOG.warn("Canal {} fora de escopo: enviando por e-mail (pedido_id={})", p.getCanalEntrega().valor(), id);
        }
        String sufixo = reenvio == null ? "" : "/reenvio/" + reenvio;
        if (!p.isEhGratuito()) {
            return new Envio(modelos.oracao(p.getEmail(), p.getNome(), p.getOracaoGerada())
                    .comChave("oracao/" + id + sufixo), false, null);
        }
        if (reenvio == null && p.getOracaoEmailSentAt() != null) {
            LOG.info("enviar_oracao_email_freemium_idempotente (event=freemium_email_oracao_already_sent, pedido_id={})",
                    id);
            return new Envio(null, true, null);
        }
        String login = p.getEmail();
        String senha = "";
        String chave = null;
        Usuario u = p.getUser();
        if (u != null) {
            chave = PREFIXO_SENHA_FREEMIUM + u.getId();
            senha = senhas.ler(chave).orElse("");
            if (u.getEmail() != null && !u.getEmail().isEmpty()) login = u.getEmail();
            // reenvio de freemium ja enviado: a senha saiu do cache no 1o envio, nao e alerta
            if (senha.isEmpty() && p.getOracaoEmailSentAt() == null) {
                LOG.warn("Senha temporária do freemium não encontrada no cache (event=freemium_senha_temp_expirada, "
                        + "pedido_id={}, user_id={})", id, u.getId());
                Sentry.captureMessage("Freemium senha temp expirou para pedido " + id, SentryLevel.WARNING);
            }
        }
        Email email = modelos.oracaoFreemium(p.getEmail(), p.getNome(), p.getOracaoGerada(), login, senha)
                .comChave("oracao-freemium/" + id + sufixo);
        return new Envio(email, true, senha.isEmpty() ? null : chave);
    }

    /** Retentativas esgotadas: erro e alerta (o Sentry vem do observador da fila). */
    @Override
    public void esgotou(UUID id, Throwable erro) {
        tx.executeWithoutResult(s -> {
            Pedido p = pedidos.findById(id).orElse(null);
            if (p == null || p.getStatus() == StatusPedido.ENVIADA) return;
            LOG.error("enviar_oracao_erro_persistente (event=enviar_oracao_failed, pedido_id={})", id);
            p.setStatus(StatusPedido.ERRO);
            alerta.registrar(id);
        });
    }
}
