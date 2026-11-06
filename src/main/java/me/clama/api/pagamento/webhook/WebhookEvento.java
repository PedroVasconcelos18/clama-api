package me.clama.api.pagamento.webhook;

import java.util.Set;
import java.util.UUID;

/**
 * Linha de payments_webhookevento (payments/models.py:69-131), so o que o webhook decide: id e status. Os campos
 * restantes (provider, external_event_id, event_type, payload, pedido_id, error_message) so sao escritos.
 */
public record WebhookEvento(UUID id, String status) {
    public static final String RECEBIDO = "RECEBIDO";
    public static final String PROCESSADO = "PROCESSADO";
    public static final String IGNORADO = "IGNORADO";
    public static final String ERRO = "ERRO";
    /** _TERMINAL_WEBHOOK_STATES: so eles curto-circuitam; RECEBIDO/ERRO reprocessam. */
    static final Set<String> TERMINAIS = Set.of(PROCESSADO, IGNORADO);

    public boolean terminal() {
        return TERMINAIS.contains(status);
    }
}
