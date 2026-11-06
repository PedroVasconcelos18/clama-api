package me.clama.api.comum.antibot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import me.clama.api.comum.retry.HttpStatusException;
import me.clama.api.comum.retry.Retentativa;

/**
 * freemium/services/turnstile_client.py. Mock (aceita token nao vazio) so no perfil test e sem segredo; sem segredo
 * fora dele a verificacao falha (IllegalStateException -> 500), como o ImproperlyConfigured do Django em producao.
 */
@Component
public class Turnstile {
    private static final Logger LOG = LoggerFactory.getLogger(Turnstile.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String segredo;
    private final String url;
    private final boolean mockPermitido;
    private final Consumer<Duration> dormir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Autowired
    public Turnstile(@Value("${TURNSTILE_SECRET_KEY:}") String segredo,
            @Value("${TURNSTILE_VERIFY_URL:https://challenges.cloudflare.com/turnstile/v0/siteverify}") String url,
            Environment env) {
        this(segredo, url, env.acceptsProfiles(Profiles.of("test")), Retentativa::dormir);
    }

    Turnstile(String segredo, String url, boolean mockPermitido, Consumer<Duration> dormir) {
        this.segredo = segredo == null ? "" : segredo;
        this.url = url;
        this.mockPermitido = mockPermitido;
        this.dormir = dormir;
    }

    /** true se a Cloudflare aprovou; false se rejeitou; TurnstileIndisponivel em rede/HTTP. */
    public boolean validar(String token, String ip) {
        if (segredo.isEmpty()) {
            if (!mockPermitido) {
                throw new IllegalStateException("TURNSTILE_SECRET_KEY obrigatorio fora do perfil de teste");
            }
            return token != null && !token.isEmpty();
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("secret", segredo);
        form.put("response", token);
        if (ip != null && !ip.isEmpty()) form.put("remoteip", ip);
        String corpo = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build();
        try {
            return Retentativa.executar(() -> chamar(req), 3, Retentativa.ESPERAS_PADRAO, Retentativa::padrao, dormir);
        } catch (HttpStatusException | UncheckedIOException e) {
            LOG.warn("Turnstile request failed: {}", e.getMessage());
            throw new TurnstileIndisponivel(e);
        }
    }

    private boolean chamar(HttpRequest req) {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            // o Django tenta ler o corpo ate em 4xx (so para log) e depois raise_for_status
            if (r.statusCode() / 100 != 2) throw new HttpStatusException(r.statusCode());
            try {
                JsonNode s = JSON.readTree(r.body()).get("success");
                return s != null && s.asBoolean(false);
            } catch (RuntimeException e) {
                return false; // corpo ilegivel em 2xx: body = {} no Python
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("verificacao interrompida", e);
        }
    }
}
