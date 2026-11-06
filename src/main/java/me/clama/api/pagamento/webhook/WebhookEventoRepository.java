package me.clama.api.pagamento.webhook;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * WebhookEventoManager.try_register e os mark_* do model, em SQL. Fora de transacao cada comando e autocommit; dentro,
 * entra na transacao do chamador.
 *
 * <p>As marcacoes so mudam linha nao terminal (RECEBIDO/ERRO): em sequencia e o mesmo do Django (que nunca marca uma
 * linha terminal, porque curto-circuita antes); com reentregas concorrentes do mesmo id, a que perde nao sobrescreve
 * o PROCESSADO da que ganhou. O retorno diz se a linha mudou.
 */
@Repository
public class WebhookEventoRepository {
    static final String MERCADO_PAGO = "MERCADO_PAGO";

    private final JdbcTemplate jdbc;

    WebhookEventoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** INSERT ... ON CONFLICT DO NOTHING e leitura: a linha nova (RECEBIDO) ou a ja existente, com o status dela. */
    public WebhookEvento registrar(String externalEventId, String tipo, String payloadJson) {
        jdbc.update("""
                INSERT INTO payments_webhookevento (created_at, updated_at, id, provider, external_event_id, event_type,
                    payload, status, error_message, pedido_id)
                VALUES (now(), now(), ?, ?, ?, ?, ?::jsonb, 'RECEBIDO', '', NULL)
                ON CONFLICT (provider, external_event_id) DO NOTHING""",
                UUID.randomUUID(), MERCADO_PAGO, externalEventId, tipo, payloadJson);
        return jdbc.queryForObject("SELECT id, status FROM payments_webhookevento WHERE provider = ? AND "
                + "external_event_id = ?", (rs, n) -> new WebhookEvento(rs.getObject(1, UUID.class), rs.getString(2)),
                MERCADO_PAGO, externalEventId);
    }

    /** mark_processed(pedido): status e pedido; error_message fica (o do fetch que falhou antes, se houve). */
    public boolean marcarProcessado(UUID id, UUID pedido) {
        return jdbc.update("UPDATE payments_webhookevento SET status = 'PROCESSADO', pedido_id = ?, updated_at = now() "
                + "WHERE id = ? AND status IN ('RECEBIDO', 'ERRO')", pedido, id) == 1;
    }

    /** mark_ignored(reason). */
    public boolean marcarIgnorado(UUID id, String motivo) {
        return marcar(id, WebhookEvento.IGNORADO, motivo);
    }

    /** mark_error(msg): reprocessavel. */
    public boolean marcarErro(UUID id, String mensagem) {
        return marcar(id, WebhookEvento.ERRO, mensagem);
    }

    private boolean marcar(UUID id, String status, String mensagem) {
        return jdbc.update("UPDATE payments_webhookevento SET status = ?, error_message = ?, updated_at = now() "
                + "WHERE id = ? AND status IN ('RECEBIDO', 'ERRO')", status, mensagem, id) == 1;
    }
}
