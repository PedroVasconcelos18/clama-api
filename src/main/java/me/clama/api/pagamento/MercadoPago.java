package me.clama.api.pagamento;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import me.clama.api.comum.retry.Retentativa;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.pedido.Pedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * clama/payments/services/mercadopago_client.py sem o SDK: POST /v1/payments (Pix) e GET /v1/payments/{id}. Retry de
 * 3 com 1/2/4 s em rede/timeout e 500/502/503/504; o resto vira {@link ErroDoProvedor} na hora.
 *
 * <p>Diferencas deliberadas do Django: chave de idempotencia fixa por pedido (o SDK sorteia uma por tentativa e um
 * retry depois de timeout pode criar o 2o Pix) e timeout de 30 s por chamada (o SDK espera 60 s com o lock do pedido
 * preso). O token so vai no header Authorization: nunca em log, mensagem ou corpo de erro.
 */
@Component
public class MercadoPago {
    private static final Logger LOG = LoggerFactory.getLogger(MercadoPago.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Set<Integer> RETENTAVEIS = Set.of(500, 502, 503, 504);
    private static final Set<String> APROVADO = Set.of("approved");
    private static final Set<String> RECUSADO = Set.of("rejected", "cancelled", "refunded", "charged_back");

    public enum StatusPagamento { APROVADO, PENDENTE, RECUSADO }

    /** PagamentoResult: so isso; o valor pago nunca e comparado (paridade, ver Etapa 9). */
    public record PagamentoMP(StatusPagamento status, String externalReference, String statusBruto) {}

    private final String token;
    private final String url;
    private final String backendUrl;
    private final Duration timeout;
    private final Consumer<Duration> dormir;
    private final HttpClient http;

    @Autowired
    public MercadoPago(@Value("${MERCADOPAGO_ACCESS_TOKEN:}") String token,
            @Value("${clama.mercadopago.url:https://api.mercadopago.com}") String url,
            @Value("${BACKEND_PUBLIC_URL:}") String backendUrl) {
        this(token, url, backendUrl, TIMEOUT, Retentativa::dormir);
    }

    MercadoPago(String token, String url, String backendUrl, Duration timeout, Consumer<Duration> dormir) {
        this.token = token == null ? "" : token;
        this.url = url.replaceAll("/+$", "");
        this.backendUrl = backendUrl == null ? "" : backendUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        this.dormir = dormir;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public CobrancaPix criarCobrancaPix(Pedido p) {
        ObjectNode payer = JSON.createObjectNode().put("email", p.getEmail());
        String nome = TextoPython.strip(p.getNome() == null ? "" : p.getNome());
        if (!nome.isEmpty()) payer.put("first_name", nome);
        String doc = p.getCpfCnpj();
        if (doc != null && !doc.isEmpty()) {
            payer.putObject("identification").put("type", doc.codePointCount(0, doc.length()) == 14 ? "CNPJ" : "CPF")
                    .put("number", doc);
        }
        ObjectNode corpo = JSON.createObjectNode()
                // centavos exatos com 2 casas (30.00, 12.34): nunca float
                .put("transaction_amount", BigDecimal.valueOf(p.getValorCentavos(), 2))
                .put("description", "Pedido Clama #" + p.getId().toString().substring(0, 8))
                .put("payment_method_id", "pix")
                .put("external_reference", p.getId().toString());
        corpo.set("payer", payer);
        if (backendUrl.startsWith("https://")) {
            corpo.put("notification_url", backendUrl + "/api/webhooks/mercadopago/");
            LOG.info("Pix criado COM notification_url (event=pix_notification_url_set)");
        } else {
            // sem notification_url o MP nao entrega webhook: o pedido so anda pelo polling
            LOG.warn("Pix criado SEM notification_url — BACKEND_PUBLIC_URL não-https; webhook não será entregue "
                    + "(event=pix_notification_url_missing, backend_url={})", backendUrl.isEmpty() ? "(vazio)" : backendUrl);
        }
        HttpRequest req = base("/v1/payments").header("Content-Type", "application/json")
                .header("X-Idempotency-Key", "clama-pedido-" + p.getId())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(corpo))).build();

        JsonNode r = executar("payment_create", req);
        JsonNode dados = r.path("point_of_interaction").path("transaction_data");
        String qr = texto(dados.get("qr_code"));
        String id = texto(r.get("id"));
        if (id == null || id.isEmpty() || "0".equals(id) || qr == null || qr.isEmpty()) {
            throw new ErroDoProvedor("Resposta do Mercado Pago sem id/QR Pix ao criar cobrança", null, r.toString());
        }
        String ticket = texto(dados.get("ticket_url"));
        return new CobrancaPix(id, qr, texto(dados.get("qr_code_base64")), ticket == null ? "" : ticket);
    }

    public PagamentoMP buscarPagamento(String id) {
        HttpRequest req = base("/v1/payments/" + URLEncoder.encode(id, StandardCharsets.UTF_8)).GET().build();
        JsonNode r = executar("payment_get", req);
        String bruto = texto(r.get("status"));
        StatusPagamento s = bruto == null ? StatusPagamento.PENDENTE : APROVADO.contains(bruto) ? StatusPagamento.APROVADO
                : RECUSADO.contains(bruto) ? StatusPagamento.RECUSADO : StatusPagamento.PENDENTE;
        return new PagamentoMP(s, texto(r.get("external_reference")), bruto);
    }

    private HttpRequest.Builder base(String caminho) {
        return HttpRequest.newBuilder(URI.create(url + caminho)).timeout(timeout)
                .header("Authorization", "Bearer " + token);
    }

    /** _execute + _request + _unwrap do Django: corpo 2xx objeto, senao ErroDoProvedor. */
    private JsonNode executar(String operacao, HttpRequest req) {
        AtomicInteger tentativa = new AtomicInteger();
        try {
            return Retentativa.executar(() -> chamar(operacao, req, tentativa.incrementAndGet()), 3,
                    Retentativa.ESPERAS_PADRAO, e -> e instanceof UncheckedIOException
                            || e instanceof ErroDoProvedor erro && erro.upstreamStatus() != null
                            && RETENTAVEIS.contains(erro.upstreamStatus()),
                    dormir);
        } catch (UncheckedIOException e) {
            throw new ErroDoProvedor("Mercado Pago inacessível em " + operacao, null, e.getCause().toString());
        }
    }

    private JsonNode chamar(String operacao, HttpRequest req, int tentativa) {
        long inicio = System.nanoTime();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log(operacao, null, tentativa, inicio, e.toString());
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chamada ao Mercado Pago interrompida", e);
        }
        int status = r.statusCode();
        log(operacao, status, tentativa, inicio, null);
        if (status >= 200 && status < 300) {
            JsonNode corpo;
            try {
                corpo = JSON.readTree(r.body());
            } catch (RuntimeException e) {
                corpo = null;
            }
            if (corpo == null || !corpo.isObject()) {
                throw new ErroDoProvedor("Corpo inesperado do Mercado Pago em " + operacao, status, r.body());
            }
            return corpo;
        }
        if (status >= 500) {
            throw new ErroDoProvedor("Mercado Pago indisponível em " + operacao + " (HTTP " + status + ")", status,
                    r.body());
        }
        throw new ErroDoProvedor("Erro do Mercado Pago em " + operacao + " (HTTP " + status + ")", status, r.body());
    }

    /** event=mercadopago_request, sem PII nem token. */
    private static void log(String operacao, Integer status, int tentativa, long inicio, String erro) {
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        if (erro != null || status >= 400) {
            LOG.warn("Mercado Pago request failed (event=mercadopago_request, operation={}, status={}, attempt={}, "
                    + "duration_ms={}, error={})", operacao, status, tentativa, ms, erro);
        } else {
            LOG.info("Mercado Pago request completed (event=mercadopago_request, operation={}, status={}, attempt={}, "
                    + "duration_ms={})", operacao, status, tentativa, ms);
        }
    }

    /** str() do valor JSON (id numerico vira "1325814423"); null se ausente ou null. */
    private static String texto(JsonNode v) {
        return v == null || v.isNull() || v.isMissingNode() ? null : v.isString() ? v.asString() : v.toString();
    }
}
