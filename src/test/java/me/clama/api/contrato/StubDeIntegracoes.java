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
 * Uma integracao HTTP num HttpServer local, um por integracao (cada uma com a sua fila): serve as respostas do cenario
 * ("mercadopago", "anthropic"), na ordem, e grava toda requisicao recebida para comparar com "<integracao>_requisicoes",
 * o que o Django mandou ao SDK. Requisicao alem das declaradas recebe 500 e aparece na comparacao. O do Mercado Pago
 * tambem atende o Turnstile dos pedidos. Resposta {"falha": "conexao"} fecha a conexao sem responder.
 */
public class StubDeIntegracoes {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NOS = JsonNodeFactory.instance;

    private final String rotulo;
    /** chave no JSON gravado -> header da requisicao (null se ausente) */
    private final Map<String, String> cabecalhos;
    private final HttpServer servidor;
    private final Deque<JsonNode> respostas = new ArrayDeque<>();
    private final List<JsonNode> recebidas = new ArrayList<>();

    /** O do Mercado Pago: grava o Authorization. */
    public StubDeIntegracoes() {
        this("requisicoes ao Mercado Pago", Map.of("autorizacao", "Authorization"));
    }

    public StubDeIntegracoes(String rotulo, Map<String, String> cabecalhos) {
        this.rotulo = rotulo;
        this.cabecalhos = cabecalhos;
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
    public synchronized void preparar(JsonNode respostasDoCenario) {
        respostas.clear();
        recebidas.clear();
        respostasDoCenario.forEach(respostas::add);
    }

    /**
     * O que o app mandou tem de ser o que o Django mandou: metodo, caminho, os cabecalhos gravados e corpo. A chave de
     * idempotencia fica de fora (o SDK do Django gera uma aleatoria). Numero compara por valor (30.0 == 30.00).
     */
    public synchronized void conferir(JsonNode esperadas, String cenario) {
        assertThat(numerosPorValor(NOS.arrayNode().addAll(recebidas)))
                .as("%s: %s", cenario, rotulo)
                .isEqualTo(numerosPorValor(esperadas.isMissingNode() ? NOS.arrayNode() : esperadas));
    }

    private void atender(HttpExchange troca) throws IOException {
        byte[] corpo = troca.getRequestBody().readAllBytes();
        ObjectNode req = NOS.objectNode();
        req.put("metodo", troca.getRequestMethod());
        String query = troca.getRequestURI().getRawQuery();
        req.put("caminho", troca.getRequestURI().getRawPath() + (query == null ? "" : "?" + query));
        cabecalhos.forEach((chave, header) -> req.put(chave, troca.getRequestHeaders().getFirst(header)));
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
        if (resposta != null && resposta.path("falha").asString("").equals("conexao")) {
            troca.close(); // falha de rede (requests.ConnectionError no gravador): fecha sem resposta
            return;
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
