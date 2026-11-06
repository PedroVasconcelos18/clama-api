package me.clama.api.pagamento.webhook;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.conta.JobBoasVindas;
import me.clama.api.conta.ProvisionamentoDeConta;
import me.clama.api.instituicao.repasse.RegistroDeRepasse;
import me.clama.api.oracao.JobGerarOracao;
import me.clama.api.pagamento.ErroDoProvedor;
import me.clama.api.pagamento.MercadoPago;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.TransicaoInvalida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
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
 * <p>Transacoes: o registro e as marcacoes de ignorado/erro sao comandos proprios (autocommit). O processamento do
 * aprovado e uma transacao so, com o pedido travado (SELECT ... FOR UPDATE): pago, repasse, conta, PROCESSADO e os
 * jobs gerar-oracao e boas-vindas (visiveis na fila so com o commit, o on_commit do Django). Notificacoes concorrentes
 * do mesmo pagamento (mesmo id ou nao) se enfileiram no lock; a 2a acha o pedido pago e responde already_paid. Erro
 * inesperado desfaz o processamento inteiro e marca ERRO depois, fora dele: a linha fica reprocessavel e o 500 faz o
 * MP reenviar.
 */
@RestController
class WebhookMercadoPagoController {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookMercadoPagoController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * 4xx do GET do pagamento tratados como 5xx (decisao do controlador, fix 1 da tarefa 5): 408 e 429 passam, e
     * 401/403 (token errado ou trocado) se corrigem com config. Ignorar descartaria para sempre um pagamento aprovado.
     * O Django retenta 429 no SDK e acaba em 500; 401/403 la descartam o pagamento em silencio, aqui nao.
     */
    static final Set<Integer> TRANSIENTES = Set.of(401, 403, 408, 429);

    private final WebhookEventoRepository eventos;
    private final MercadoPago mercadoPago;
    private final PedidoRepository pedidos;
    private final RegistroDeRepasse repasse;
    private final ProvisionamentoDeConta provisionamento;
    private final Fila fila;
    private final JobGerarOracao gerar;
    private final JobBoasVindas boasVindas;
    private final TransactionTemplate tx;

    WebhookMercadoPagoController(WebhookEventoRepository eventos, MercadoPago mercadoPago, PedidoRepository pedidos,
            RegistroDeRepasse repasse, ProvisionamentoDeConta provisionamento, Fila fila, JobGerarOracao gerar,
            JobBoasVindas boasVindas, TransactionTemplate tx) {
        this.eventos = eventos;
        this.mercadoPago = mercadoPago;
        this.pedidos = pedidos;
        this.repasse = repasse;
        this.provisionamento = provisionamento;
        this.fila = fila;
        this.gerar = gerar;
        this.boasVindas = boasVindas;
        this.tx = tx;
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

        MercadoPago.PagamentoMP pagamento;
        try {
            pagamento = mercadoPago.buscarPagamento(dataId);
        } catch (ErroDoProvedor e) {
            Integer upstream = e.upstreamStatus();
            if (upstream != null && upstream >= 400 && upstream < 500 && !TRANSIENTES.contains(upstream)) {
                // 4xx (pagamento inexistente, requisicao recusada) nao e transiente: 200, sem loop de reenvio
                eventos.marcarIgnorado(evento.id(), "fetch 4xx do Mercado Pago: " + upstream);
                Sentry.captureMessage("Mercado Pago webhook: fetch 4xx (config/credencial ou pagamento inexistente)",
                        SentryLevel.ERROR);
                LOG.warn("Mercado Pago webhook: payment fetch 4xx (non-retriable) (event=mercadopago_webhook_fetch_4xx, "
                        + "external_event_id={}, upstream_status={})", externalId, upstream);
                return status("ignored");
            }
            // 5xx/rede e os 4xx de TRANSIENTES: linha reprocessavel e 500 (o MP reenvia)
            eventos.marcarErro(evento.id(), ate2000(mensagem(e)));
            Sentry.captureException(e);
            LOG.warn("Mercado Pago webhook: payment fetch failed (event=mercadopago_webhook_fetch_failed, "
                    + "external_event_id={}, data_id={})", externalId, dataId);
            return erro500("fetch_failed", "Payment fetch failed");
        }
        if (pagamento.status() != MercadoPago.StatusPagamento.APROVADO) {
            eventos.marcarIgnorado(evento.id(), "pagamento não-aprovado: "
                    + Objects.toString(pagamento.statusBruto(), "None"));
            return status("ignored");
        }
        String referencia = pagamento.externalReference();
        if (referencia == null || referencia.isEmpty()) {
            eventos.marcarIgnorado(evento.id(), "pagamento sem external_reference");
            return status("ignored");
        }

        Boolean achou;
        try {
            achou = tx.execute(s -> processar(evento, uuidDoDjango(referencia)));
        } catch (TransicaoInvalida e) {
            // 409 do guard (pedido ja pago, inclusive por outra notificacao do mesmo pagamento): sucesso idempotente.
            // Linha ja terminal = reentrega concorrente do mesmo id que acabou de processar.
            return status(eventos.marcarIgnorado(evento.id(), "pedido já processado (idempotente)") ? "already_paid"
                    : "already_processed");
        } catch (RuntimeException e) {
            eventos.marcarErro(evento.id(), ate2000(mensagem(e)));
            Sentry.captureException(e);
            LOG.error("Mercado Pago webhook error (event=mercadopago_webhook_error, external_event_id={})", externalId,
                    e);
            return erro500("internal_error", "Processing failed");
        }
        if (!Boolean.TRUE.equals(achou)) {
            eventos.marcarIgnorado(evento.id(), "pedido não encontrado: " + referencia);
            return status("ignored");
        }
        LOG.info("Mercado Pago webhook: payment confirmed (event=mercadopago_webhook_payment_confirmed, pedido_id={}, "
                + "external_event_id={})", referencia, externalId);
        return status("ok");
    }

    /** O bloco transaction.atomic() do Django. false: pedido inexistente (ou referencia que nao e UUID). */
    private boolean processar(WebhookEvento evento, UUID pedidoId) {
        Pedido p = pedidoId == null ? null : pedidos.travar(pedidoId).orElse(null);
        if (p == null) {
            return false;
        }
        p.marcarComoPago();
        repasse.registrar(p);
        ProvisionamentoDeConta.Conta conta = provisionamento.provisionarOuVincular(p);
        if (!eventos.marcarProcessado(evento.id(), p.getId())) {
            LOG.warn("Mercado Pago webhook: evento ja terminal ao marcar processado (copia paralela) "
                    + "(event=mercadopago_webhook_mark_processed_skipped, webhook_evento_id={}, pedido_id={})",
                    evento.id(), p.getId());
        }
        fila.enfileirar(gerar, JobGerarOracao.instancia(p.getId()), p.getId());
        // credenciais so quando a conta nasceu aqui; no vinculo o recibo e a propria oracao
        if (conta.criada()) {
            Long userId = conta.usuario().getId();
            fila.enfileirar(boasVindas, JobBoasVindas.instancia(userId), userId);
        }
        return true;
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

    /**
     * O uuid.UUID(ref) do UUIDField: aceita "urn:uuid:", chaves, hifens em qualquer lugar e maiusculas; null se nao
     * for UUID (o get() levanta ValidationError e o Django marca "pedido nao encontrado").
     */
    static UUID uuidDoDjango(String ref) {
        String hex = ref.replace("urn:", "").replace("uuid:", "").replaceAll("^[{}]+|[{}]+$", "").replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
    }

    /** str(exc): a mensagem (o nome da classe se nao houver). */
    private static String mensagem(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    /** str(exc)[:2000], em code points como o Python. */
    private static String ate2000(String s) {
        return s.codePointCount(0, s.length()) <= 2000 ? s : s.substring(0, s.offsetByCodePoints(0, 2000));
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
