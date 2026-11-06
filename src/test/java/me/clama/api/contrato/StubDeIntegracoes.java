package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * O Mercado Pago (e qualquer outra integracao HTTP que o app chame, como o Turnstile) num HttpServer local: serve as
 * respostas "mercadopago" do cenario, na ordem, e grava toda requisicao recebida para comparar com
 * "mercadopago_requisicoes", o que o Django mandou ao SDK. Requisicao alem das declaradas recebe 500 e aparece na
 * comparacao.
 */
public class StubDeIntegracoes {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NOS = JsonNodeFactory.instance;

    private final HttpServer servidor;
    private final Deque<JsonNode> respostas = new ArrayDeque<>();
    private final List<JsonNode> recebidas = new ArrayList<>();

    public StubDeIntegracoes() {
        try {
            servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/", this::atender);
        servidor.start();
    }

    public String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    public void parar() {
        servidor.stop(0);
    }

    /** Respostas do cenario, em ordem; zera as requisicoes gravadas. */
    public synchronized void preparar(JsonNode mercadopago) {
        respostas.clear();
        recebidas.clear();
        mercadopago.forEach(respostas::add);
    }

    /**
     * O que o app mandou tem de ser o que o Django mandou: metodo, caminho, Authorization e corpo. A chave de
     * idempotencia fica de fora (o SDK do Django gera uma aleatoria). Numero compara por valor (30.0 == 30.00).
     */
    public synchronized void conferir(JsonNode esperadas, String cenario) {
        assertThat(numerosPorValor(NOS.arrayNode().addAll(recebidas)))
                .as("%s: requisicoes ao Mercado Pago", cenario)
                .isEqualTo(numerosPorValor(esperadas.isMissingNode() ? NOS.arrayNode() : esperadas));
    }

    private void atender(HttpExchange troca) throws IOException {
        byte[] corpo = troca.getRequestBody().readAllBytes();
        ObjectNode req = NOS.objectNode();
        req.put("metodo", troca.getRequestMethod());
        String query = troca.getRequestURI().getRawQuery();
        req.put("caminho", troca.getRequestURI().getRawPath() + (query == null ? "" : "?" + query));
        req.put("autorizacao", troca.getRequestHeaders().getFirst("Authorization"));
        if (corpo.length > 0) {
            String texto = new String(corpo, StandardCharsets.UTF_8);
            JsonNode json;
            try {
                json = JSON.readTree(texto);
            } catch (RuntimeException naoEhJson) { // ex.: o form do Turnstile
                json = NOS.stringNode(texto);
            }
            req.set("corpo", json);
        }
        JsonNode resposta;
        synchronized (this) {
            recebidas.add(req);
            resposta = respostas.poll();
        }
        byte[] saida = resposta == null ? "{\"stub\":\"requisicao nao declarada no cenario\"}".getBytes(StandardCharsets.UTF_8)
                : JSON.writeValueAsBytes(resposta.get("corpo"));
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(resposta == null ? 500 : resposta.get("status").asInt(), saida.length);
        troca.getResponseBody().write(saida);
        troca.close();
    }

    static JsonNode numerosPorValor(JsonNode no) {
        if (no.isNumber()) {
            return NOS.numberNode(no.decimalValue().stripTrailingZeros());
        }
        if (no.isObject()) {
            ObjectNode o = NOS.objectNode();
            for (Map.Entry<String, JsonNode> e : no.properties()) {
                o.set(e.getKey(), numerosPorValor(e.getValue()));
            }
            return o;
        }
        if (no.isArray()) {
            ArrayNode a = NOS.arrayNode();
            no.forEach(v -> a.add(numerosPorValor(v)));
            return a;
        }
        return no;
    }
}
