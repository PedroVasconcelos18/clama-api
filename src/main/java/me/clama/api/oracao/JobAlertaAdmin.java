package me.clama.api.oracao;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Duration;
import java.util.UUID;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.email.EnviadorDeEmail;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * enviar_alerta_admin_task (notifications/tasks.py:488-576): e-mail de texto para ADMIN_ALERT_EMAIL, assunto e corpo
 * copiados (com PII, como no Django). Sem retry; falha de envio vai pro Sentry e o job termina com o
 * capture_message "Pedido em ERRO: <id>".
 */
@Component
public class JobAlertaAdmin implements TipoDeJob<UUID> {
    private static final Logger LOG = LoggerFactory.getLogger(JobAlertaAdmin.class);
    public static final String NOME = "alerta-admin";

    private final PedidoRepository pedidos;
    private final EnviadorDeEmail enviador;
    private final TransactionTemplate tx;
    private final String adminEmail;
    private final String frontendUrl;

    JobAlertaAdmin(PedidoRepository pedidos, EnviadorDeEmail enviador, TransactionTemplate tx,
            @Value("${ADMIN_ALERT_EMAIL:contato@clama.me}") String adminEmail,
            @Value("${FRONTEND_URL:http://localhost:5173}") String frontendUrl) {
        this.pedidos = pedidos;
        this.enviador = enviador;
        this.tx = tx;
        this.adminEmail = adminEmail;
        this.frontendUrl = frontendUrl;
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
        return Duration.ZERO;
    }

    @Override
    public int maxRetentativas() {
        return 0;
    }

    @Override
    public void executar(UUID id) {
        Email email = tx.execute(s -> {
            Pedido p = pedidos.findById(id).orElse(null);
            if (p == null) {
                LOG.warn("alerta_admin_pedido_nao_encontrado (event=admin_alert_pedido_not_found, pedido_id={})", id);
                return null;
            }
            if (p.getStatus() != StatusPedido.ERRO) {
                LOG.info("alerta_admin_status_mudou (event=admin_alert_status_changed, pedido_id={}, current_status={})",
                        id, p.getStatus().valor());
                return null;
            }
            return montar(p);
        });
        if (email == null) return;
        try {
            enviador.enviar(email);
            LOG.info("alerta_admin_enviado (event=admin_alert_sent, pedido_id={})", id);
        } catch (RuntimeException e) {
            LOG.error("alerta_admin_erro_email (event=admin_alert_email_failed, pedido_id={})", id, e);
            Sentry.captureException(e);
        }
        Sentry.captureMessage("Pedido em ERRO: " + id, SentryLevel.ERROR);
    }

    private Email montar(Pedido p) {
        String id = p.getId().toString();
        String corpo = "Um pedido entrou em status ERRO e requer atenção.\n\n"
                + "ID do Pedido: " + id + "\n"
                + "Nome: " + p.getNome() + "\n"
                + "Email: " + p.getEmail() + "\n"
                + "Telefone: " + ou(p.getTelefone(), "Não informado") + "\n"
                + "Plano: " + p.getPlano().getNome() + "\n"
                + "Valor: " + p.valorReaisStr() + "\n"
                + "Canal de Entrega: " + p.getCanalEntrega().valor() + "\n"
                + "Número de Retentativas: " + p.getRetryCount() + "\n\n"
                + "Último Erro:\n" + ou(p.getLastError(), "Não registrado") + "\n\n"
                + "---\n"
                + "Acesse o admin para mais detalhes: " + frontendUrl + "/admin/pedidos/" + id + "\n";
        // a chave muda a cada rodada de reagendamento: um erro novo do mesmo pedido alerta de novo
        return new Email(adminEmail, "[Clama] Pedido em ERRO - " + id.substring(0, 8), corpo, null)
                .comChave("alerta/" + id + "/" + p.getRetryCount());
    }

    private static String ou(String v, String padrao) {
        return v == null || v.isEmpty() ? padrao : v;
    }
}
