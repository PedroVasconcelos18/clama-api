package me.clama.api.pagamento.webhook;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MercadoPagoWebhookView (payments/api/webhooks.py), passo a passo do mapa §4.4, com as mesmas respostas. A assinatura
 * ja foi conferida pelo {@link AssinaturaMercadoPago}. O estado vem sempre do GET ao Mercado Pago (nunca do corpo),
 * feito sem transacao aberta.
 *
 * <p>O registro e as marcacoes de ignorado antes do GET sao comandos proprios (autocommit). Do GET em diante e o
 * {@link ProcessamentoDePagamento}, o mesmo caminho do reprocessar do admin (D9); aqui so vira resposta HTTP.
 */
@RestController
class WebhookMercadoPagoController {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookMercadoPagoController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebhookEventoRepository eventos;
    private final ProcessamentoDePagamento processamento;

    WebhookMercadoPagoController(WebhookEventoRepository eventos, ProcessamentoDePagamento processamento) {
        this.eventos = eventos;
        this.processamento = processamento;
    }

    @PostMapping(AssinaturaMercadoPago.CAMINHO)
    @LimiteDeTaxa.Nenhum
    ResponseEntity<Map<String, Object>> receber(@RequestBody(required = false) byte[] corpo, HttpServletRequest req) {
        ObjectNode payload = objeto(corpo);
        JsonNode idNotificacao = payload.get("id");
        if (idNotificacao == null || idNotificacao.isNull()) {
            LOG.warn("Mercado Pago webhook: missing notification id (event=mercadopago_webhook_invalid, "
                    + "reason=missing_id)");
            return ResponseEntity.badRequest().body(Map.of("error", erroOrdenado("invalid_payload",
                    "Missing notification id")));
        }
        String externalId = texto(idNotificacao);
        JsonNode tipoNode = payload.get("type");
        String tipo = tipoNode == null || tipoNode.isNull() ? "" : texto(tipoNode);

        WebhookEvento evento = eventos.registrar(externalId, tipo, JSON.writeValueAsString(payload));
        if (evento.terminal()) {
            LOG.info("Mercado Pago webhook: already processed (event=mercadopago_webhook_duplicate, "
                    + "external_event_id={})", externalId);
            return status("already_processed");
        }
        if (!tipo.equals("payment")) {
            eventos.marcarIgnorado(evento.id(), "tipo não-payment: " + tipo);
            return status("ignored");
        }
        // o data.id da query e o valor assinado; o do corpo nunca e usado
        String dataId = AssinaturaMercadoPago.parametro(req.getQueryString(), "data.id");
        if (dataId.isEmpty()) {
            eventos.marcarIgnorado(evento.id(), "data.id ausente no query");
            return status("ignored");
        }

        ProcessamentoDePagamento.Resultado r = processamento.processar(evento.id(), externalId, dataId, () -> {});
        return switch (r) {
            case FALHA_NO_FETCH -> erro500("fetch_failed", "Payment fetch failed");
            case FALHA_INTERNA -> erro500("internal_error", "Processing failed");
            default -> status(r.status);
        };
    }

    /** request.data se for objeto JSON, senao {} (lista, vazio ou corpo que nao e JSON). */
    private static ObjectNode objeto(byte[] corpo) {
        try {
            JsonNode n = corpo == null || corpo.length == 0 ? null : JSON.readTree(corpo);
            if (n instanceof ObjectNode o) {
                return o;
            }
        } catch (RuntimeException naoEhJson) {
            // {} -> 400 invalid_payload
        }
        return JSON.createObjectNode();
    }

    /** str() do valor JSON: texto como esta, numero como numero. */
    private static String texto(JsonNode v) {
        return v.isString() ? v.asString() : v.toString();
    }

    private static ResponseEntity<Map<String, Object>> status(String status) {
        return ResponseEntity.ok(Map.of("status", status));
    }

    private static ResponseEntity<Map<String, Object>> erro500(String code, String message) {
        return ResponseEntity.internalServerError().body(Map.of("error", erroOrdenado(code, message)));
    }

    /** Map.of tem ordem salgada por JVM; o Django emite code antes de message. */
    private static Map<String, String> erroOrdenado(String code, String message) {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
