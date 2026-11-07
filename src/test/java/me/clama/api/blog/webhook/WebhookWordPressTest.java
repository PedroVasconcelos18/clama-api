package me.clama.api.blog.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.api.FormularioDjango;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** POST /api/webhooks/wordpress/ de ponta a ponta: filtro HMAC sobre o corpo cru, controller, registro e fila. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "WORDPRESS_WEBHOOK_SECRET=" + WebhookWordPressTest.SEGREDO})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class WebhookWordPressTest {
    static final String SEGREDO = "segredo-teste-wp";
    static final String CAMINHO = "/api/webhooks/wordpress/";
    static final ObjectMapper JSON = new ObjectMapper();

    @Value("${local.server.port}")
    int porta;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE scheduled_tasks, payments_webhookevento CASCADE");
    }

    static String assinar(byte[] corpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SEGREDO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(corpo));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(String caminho, String tipo, String corpo, String assinatura, String cookie)
            throws Exception {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).header("Content-Type", tipo);
        if (assinatura != null) {
            b.header("X-Clama-Signature", assinatura);
        }
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> form(String corpo) throws Exception {
        return post(CAMINHO, "application/x-www-form-urlencoded", corpo,
                assinar(corpo.getBytes(StandardCharsets.UTF_8)), null);
    }

    private JsonNode payload(String eventoId) {
        return JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM payments_webhookevento WHERE "
                + "external_event_id = ?", String.class, eventoId));
    }

    private List<Map<String, Object>> jobs() {
        return jdbc.queryForList("SELECT task_name, task_instance, convert_from(task_data, 'UTF8') AS dado "
                + "FROM scheduled_tasks");
    }

    @Test
    void formEncodedAceito() throws Exception {
        HttpResponse<String> r = form("evento_id=wp-1&tipo=post_publicado&wp_post_id=7&titulo=Primeiro"
                + "&titulo=T%C3%ADtulo+final&vazio=&protegido_por_senha=0");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"status\":\"ok\"}");
        // o ultimo valor de cada chave, tudo texto
        assertThat(payload("wp-1")).isEqualTo(JSON.readTree("{\"evento_id\": \"wp-1\", \"tipo\": \"post_publicado\","
                + " \"wp_post_id\": \"7\", \"titulo\": \"Título final\", \"vazio\": \"\","
                + " \"protegido_por_senha\": \"0\"}"));
        String id = jdbc.queryForObject("SELECT id::text FROM payments_webhookevento WHERE external_event_id = 'wp-1'"
                + " AND provider = 'WORDPRESS' AND event_type = 'post_publicado' AND status = 'RECEBIDO'"
                + " AND error_message = ''", String.class);
        assertThat(jobs()).containsExactly(Map.of("task_name", "sincronizar-espelho", "task_instance",
                "sincronizar-espelho/" + id, "dado", "\"" + id + "\""));
    }

    @Test
    void jsonGuardaOsTiposOriginais() throws Exception {
        String corpo = "{\"evento_id\": \" wp-2 \", \"tipo\": \"post_removido\", \"wp_post_id\": 12.7,"
                + " \"protegido_por_senha\": true, \"extra\": {\"a\": [1, null]}}";
        HttpResponse<String> r = post(CAMINHO, "application/json", corpo,
                "sha256=" + assinar(corpo.getBytes(StandardCharsets.UTF_8)), null);
        assertThat(r.body()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(payload("wp-2")).isEqualTo(JSON.readTree(corpo)); // evento_id sem strip no payload
        assertThat(jobs()).hasSize(1);
    }

    /**
     * O HMAC e dos bytes exatos, e o controller ainda le o corpo inteiro: nada consumiu o form antes (getParameter
     * leria o stream). A query string nao entra no payload (request.data so le o corpo).
     */
    @Test
    void corpoIntactoParaOController() throws Exception {
        String corpo = "evento_id=wp-3&tipo=post_atualizado&slug=a%7Eb&&sem_igual&x=1%2B1+2";
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> r = post(CAMINHO + "?tipo=post_comentado&q=1", "application/x-www-form-urlencoded",
                corpo, assinar(bytes), null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(payload("wp-3")).isEqualTo(JSON.readTree("{\"evento_id\": \"wp-3\", \"tipo\": \"post_atualizado\","
                + " \"slug\": \"a~b\", \"sem_igual\": \"\", \"x\": \"1+1 2\"}"));
        // um byte a mais no corpo e outra assinatura
        assertThat(post(CAMINHO, "application/x-www-form-urlencoded", corpo + "&", assinar(bytes), null).statusCode())
                .isEqualTo(401);
    }

    /**
     * Reentrega com o evento ainda RECEBIDO: o Django enfileiraria outra task; aqui a instancia deterministica
     * sincronizar-espelho/&lt;id&gt; deixa um job so. Terminal: already_processed, sem job.
     */
    @Test
    void reentregaNaoDuplica() throws Exception {
        String corpo = "evento_id=wp-4&tipo=post_publicado&wp_post_id=9";
        assertThat(form(corpo).body()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(form(corpo + "&titulo=outro").body()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments_webhookevento", Integer.class)).isEqualTo(1);
        assertThat(payload("wp-4").has("titulo")).as("payload original").isFalse();
        assertThat(jobs()).hasSize(1);

        jdbc.update("UPDATE payments_webhookevento SET status = 'PROCESSADO'");
        jdbc.execute("TRUNCATE scheduled_tasks");
        assertThat(form(corpo).body()).isEqualTo("{\"status\":\"already_processed\"}");
        assertThat(jobs()).isEmpty();
    }

    @Test
    void ignoradoSoMudaOStatus() throws Exception {
        assertThat(form("evento_id=wp-5&tipo=comentario_novo").body()).isEqualTo("{\"status\":\"ignored\"}");
        assertThat(jdbc.queryForObject("SELECT status || '/' || error_message FROM payments_webhookevento",
                String.class)).isEqualTo("IGNORADO/");
        assertThat(jobs()).isEmpty();
    }

    /** Decisao: id ou tipo maior que a coluna (120/80) e 400 invalid_payload, nao o 500 do DataError do Django. */
    @Test
    void campoMaiorQueAColunaE400() throws Exception {
        assertThat(form("evento_id=" + "é".repeat(120) + "&tipo=post_publicado").body())
                .as("120 caracteres cabem").isEqualTo("{\"status\":\"ok\"}");
        HttpResponse<String> longo = form("evento_id=" + "a".repeat(121) + "&tipo=post_publicado");
        assertThat(longo.statusCode()).isEqualTo(400);
        assertThat(longo.body()).isEqualTo("{\"status\":\"invalid_payload\"}");
        assertThat(form("evento_id=wp-6&tipo=" + "t".repeat(81)).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments_webhookevento", Integer.class)).isEqualTo(1);
    }

    /** Decisao: JSON que nao e objeto e 400 invalid_payload (o Django daria 500 no data.get). */
    @Test
    void jsonQueNaoEObjeto() throws Exception {
        for (String corpo : List.of("[1]", "\"x\"", "null")) {
            HttpResponse<String> r = post(CAMINHO, "application/json", corpo,
                    assinar(corpo.getBytes(StandardCharsets.UTF_8)), null);
            assertThat(r.statusCode()).as(corpo).isEqualTo(400);
            assertThat(r.body()).isEqualTo("{\"status\":\"invalid_payload\"}");
        }
        HttpResponse<String> quebrado = post(CAMINHO, "application/json", "{x", assinar("{x".getBytes(
                StandardCharsets.UTF_8)), null);
        assertThat(quebrado.statusCode()).isEqualTo(400);
        assertThat(quebrado.body()).contains("JSON parse error");
    }

    /**
     * RequestDataTooBig: corpo acima de DATA_UPLOAD_MAX_MEMORY_SIZE e o 400 HTML padrao do Django, antes do HMAC e sem
     * ir inteiro para a memoria, com Content-Length e chunked. No limite exato segue para o HMAC (401).
     */
    @Test
    void corpoAcimaDoLimiteE400() throws Exception {
        byte[] grande = new byte[FormularioDjango.MAX_CAMPO + 1];
        java.util.Arrays.fill(grande, (byte) 'a');
        URI uri = URI.create("http://localhost:" + porta + CAMINHO);
        for (HttpRequest.BodyPublisher corpo : List.of(HttpRequest.BodyPublishers.ofByteArray(grande),
                HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(grande)))) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).POST(corpo)
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-Clama-Signature",
                            assinar(grande)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as("Content-Length %s", corpo.contentLength()).isEqualTo(400);
            // o Django manda "text/html; charset=utf-8"; o Tomcat sempre remonta o header sem o espaco
            assertThat(r.headers().firstValue("Content-Type")).hasValue("text/html;charset=utf-8");
            assertThat(r.body()).isEqualTo("\n<!doctype html>\n<html lang=\"en\">\n<head>\n  <title>Bad Request (400)"
                    + "</title>\n</head>\n<body>\n  <h1>Bad Request (400)</h1><p></p>\n</body>\n</html>\n");
        }
        assertThat(post(CAMINHO, "application/x-www-form-urlencoded", "a".repeat(FormularioDjango.MAX_CAMPO), "0",
                null).statusCode()).as("no limite").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments_webhookevento", Integer.class)).isZero();
    }

    /** O filtro roda antes do cookie (o middleware do Django vem antes da autenticacao do DRF). */
    @Test
    void assinaturaAntesDoCookie() throws Exception {
        HttpResponse<String> r = post(CAMINHO, "application/x-www-form-urlencoded", "evento_id=wp-7&tipo=x", "0",
                "clama_access=token-invalido");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(r.body()).get("error").get("code").asString()).isEqualTo("unauthorized");
    }

    /** Caminho codificado nao escapa do filtro chegando ao controller (a rota negada por padrao responde antes). */
    @Test
    void caminhoCodificadoNaoChegaAoController() throws Exception {
        HttpResponse<String> r = post("/api/webhooks/%77ordpress/", "application/x-www-form-urlencoded",
                "evento_id=wp-8&tipo=post_publicado", null, null);
        assertThat(r.statusCode()).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments_webhookevento", Integer.class)).isZero();
    }
}
