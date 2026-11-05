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
import tools.jackson.databind.ObjectMapper;

/** POST /emails da Resend com o mesmo payload do Anymail e 3 tentativas (1s, 2s) como o with_retry. */
public class ResendEnviador implements EnviadorDeEmail {
    static final String REPLY_TO = "contato@clama.me";

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
    public void enviar(Email e) {
        if (chave == null || chave.isBlank()) throw new IllegalStateException("RESEND_API_KEY ausente");
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("from", de);
        corpo.put("to", List.of(e.para()));
        corpo.put("subject", e.assunto());
        corpo.put("text", e.texto());
        if (e.html() != null) corpo.put("html", e.html());
        corpo.put("reply_to", List.of(REPLY_TO));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + chave)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();
        Retentativa.executar(() -> {
            try {
                int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status >= 400) throw new HttpStatusException(status);
                return null;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("envio interrompido", ex);
            }
        }, 3, Retentativa.ESPERAS_PADRAO, Retentativa::padrao, dormir);
    }
}
