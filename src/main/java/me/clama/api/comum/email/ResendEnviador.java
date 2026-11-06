package me.clama.api.comum.email;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import me.clama.api.comum.retry.HttpStatusException;
import me.clama.api.comum.retry.Retentativa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** POST /emails da Resend com o mesmo payload do Anymail e 3 tentativas (1s, 2s) como o with_retry. */
public class ResendEnviador implements EnviadorDeEmail {
    private static final Logger LOG = LoggerFactory.getLogger(ResendEnviador.class);
    public static final String REPLY_TO = "contato@clama.me";

    private final String chave;
    private final String url;
    private final String de;
    private final ObjectMapper json;
    private final Consumer<Duration> dormir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ResendEnviador(String chave, String urlBase, String de, ObjectMapper json, Consumer<Duration> dormir) {
        this.chave = chave;
        this.url = (urlBase.endsWith("/") ? urlBase : urlBase + "/") + "emails";
        this.de = de;
        this.json = json;
        this.dormir = dormir;
    }

    @Override
    public ResultadoEnvio enviar(Email e) {
        if (chave == null || chave.isBlank()) throw new IllegalStateException("RESEND_API_KEY ausente");
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("from", de);
        corpo.put("to", List.of(e.para()));
        corpo.put("subject", e.assunto());
        corpo.put("text", e.texto());
        if (e.html() != null) corpo.put("html", e.html());
        corpo.put("reply_to", List.of(REPLY_TO));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + chave)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)));
        // retentativa (aqui ou do job) com a mesma chave nao duplica o e-mail por 24 h
        if (e.chaveDeIdempotencia() != null) b.header("Idempotency-Key", e.chaveDeIdempotencia());
        HttpRequest req = b.build();
        return Retentativa.executar(() -> {
            try {
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                // 3xx tambem e erro (o HttpClient nao segue redirect); so 5xx e retentado
                if (r.statusCode() == 409 && ehChaveJaUsada(r.body())) {
                    LOG.warn("Resend 409 invalid_idempotent_request: e-mail ja enviado (chave {})", e.chaveDeIdempotencia());
                    return ResultadoEnvio.JA_ENVIADO;
                }
                if (r.statusCode() / 100 != 2) throw new HttpStatusException(r.statusCode(), r.body());
                return ResultadoEnvio.ENVIADO;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("envio interrompido", ex);
            }
        }, 3, Retentativa.ESPERAS_PADRAO, Retentativa::padrao, dormir);
    }

    /** concurrent_idempotent_requests (outro envio em curso) fica como erro: o retry do job resolve. */
    private boolean ehChaveJaUsada(String body) {
        try {
            JsonNode n = json.readTree(body);
            return "invalid_idempotent_request".equals(n.path("name").asString(null))
                    || "invalid_idempotent_request".equals(n.path("type").asString(null));
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
