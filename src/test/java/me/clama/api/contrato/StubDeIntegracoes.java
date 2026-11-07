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
 * tambem atende o Turnstile dos pedidos. Resposta {"falha": "conexao"} fecha a conexao sem responder. Resposta pode
 * trazer "headers" (X-WP-TotalPages, Location, X-Robots-Tag, Content-Type) e "corpo_texto" no lugar do "corpo" JSON
 * (robots.txt, HTML, corpo que nao e JSON).
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
    private boolean quedaPersistente;
    /** A requisicao cuja conexao o stub derrubou por ultimo (so com quedaPersistente). */
    private JsonNode ultimaQueda;

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

    /**
     * O endpoint que derrubou a conexao derruba tambem as repeticoes identicas seguintes, sem consumir resposta: o
     * java.net.http.HttpClient reenvia sozinho o GET de conexao caida (ate 5 vezes), e sem isto o reenvio levaria a
     * resposta da proxima URL. So para quem nao retenta por conta propria (os monitores do blog, um GET por URL); o
     * stub do WordPress nao usa, porque la a retentativa do app e uma requisicao identica que consome a proxima
     * resposta gravada.
     */
    public StubDeIntegracoes comQuedaPersistente() {
        quedaPersistente = true;
        return this;
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
        ultimaQueda = null;
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

    /**
     * Divergencia aceita (REDE_CAIDA do blog, opcao c): o java.net.http.HttpClient repete sozinho o GET cuja conexao
     * caiu. Igual ao gravado passa; senao, repeticoes seguidas da mesma requisicao viram uma so, dos dois lados, o resto
     * tem de bater (metodo, caminho, cabecalhos, corpo, ordem) e o Java tem de ter mandado mais que o gravado.
     *
     * @return true se tolerou repeticoes (o que o cenario na lista exige de pelo menos um stub)
     */
    public synchronized boolean conferirComRepeticoes(JsonNode esperadas, String cenario) {
        JsonNode gravadas = numerosPorValor(esperadas.isMissingNode() ? NOS.arrayNode() : esperadas);
        JsonNode obtidas = numerosPorValor(NOS.arrayNode().addAll(recebidas));
        if (obtidas.equals(gravadas)) {
            return false;
        }
        assertThat(semRepeticoes(obtidas)).as("%s: %s (repeticoes seguidas colapsadas)", cenario, rotulo)
                .isEqualTo(semRepeticoes(gravadas));
        assertThat(obtidas.size()).as("%s: %s a mais que o gravado", cenario, rotulo).isGreaterThan(gravadas.size());
        return true;
    }

    static JsonNode semRepeticoes(JsonNode lista) {
        ArrayNode a = NOS.arrayNode();
        lista.forEach(v -> {
            if (a.isEmpty() || !a.get(a.size() - 1).equals(v)) {
                a.add(v);
            }
        });
        return a;
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
        boolean queda;
        synchronized (this) {
            boolean reenvio = quedaPersistente && req.equals(ultimaQueda);
            recebidas.add(req);
            resposta = reenvio ? null : respostas.poll();
            queda = reenvio || resposta != null && resposta.path("falha").asString("").equals("conexao");
            ultimaQueda = queda ? req : null;
        }
        if (queda) {
            troca.close(); // falha de rede (requests.ConnectionError no gravador): fecha sem resposta
            return;
        }
        byte[] saida;
        if (resposta == null) {
            saida = "{\"stub\":\"requisicao nao declarada no cenario\"}".getBytes(StandardCharsets.UTF_8);
        } else if (resposta.has("corpo_texto")) {
            saida = resposta.get("corpo_texto").asString().getBytes(StandardCharsets.UTF_8);
        } else {
            saida = JSON.writeValueAsBytes(resposta.get("corpo"));
        }
        troca.getResponseHeaders().set("Content-Type", resposta != null && resposta.has("corpo_texto")
                ? "text/plain; charset=utf-8" : "application/json");
        if (resposta != null) {
            resposta.path("headers").properties().forEach(h -> troca.getResponseHeaders().set(h.getKey(),
                    h.getValue().asString()));
        }
        // -1: sem corpo (0 seria chunked)
        troca.sendResponseHeaders(resposta == null ? 500 : resposta.get("status").asInt(),
                saida.length == 0 ? -1 : saida.length);
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
