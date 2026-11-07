package me.clama.api.blog.wordpress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import me.clama.api.comum.retry.HttpStatusException;
import me.clama.api.comum.retry.Retentativa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * services/wordpress_client.py, so a leitura (a escrita era da exportacao do Epic 4). HTTP Basic com a Application
 * Password sem espacos, 15 s de timeout, 3 tentativas (1 s e 2 s de espera) em rede e 5xx; 4xx, 429 inclusive, nao
 * retenta. Toda falha vira {@link WordPressIndisponivel}: "nao consegui perguntar" nunca e "nao existe".
 * Nao abre transacao: quem chama faz o GET antes de abrir a de escrita (R10).
 */
@Component
public class ClienteWordPress {
    private static final Logger LOG = LoggerFactory.getLogger(ClienteWordPress.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int POR_PAGINA_MAX = 100;
    private static final String CAMINHO = "/wp-json/wp/v2/posts";
    private static final String CAMPOS = "id,slug,title,status,date_gmt,link,password";

    private final String baseUrl;
    private final String usuario;
    private final String senha;
    private final Consumer<Duration> dormir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Autowired
    public ClienteWordPress(@Value("${WORDPRESS_API_URL:}") String baseUrl,
            @Value("${WORDPRESS_API_USER:}") String usuario,
            @Value("${WORDPRESS_API_APP_PASSWORD:}") String senha) {
        this(baseUrl, usuario, senha, Retentativa::dormir);
    }

    ClienteWordPress(String baseUrl, String usuario, String senha, Consumer<Duration> dormir) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.usuario = usuario == null ? "" : usuario;
        this.senha = senha == null ? "" : senha;
        this.dormir = dormir;
    }

    public boolean configurado() {
        return !baseUrl.isEmpty() && !usuario.isEmpty() && !senha.isEmpty();
    }

    /** Lista vazia = o WordPress respondeu e nao tem o post (Optional vazio). */
    public Optional<PostWp> buscarPorSlug(String slug) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("slug", slug);
        p.put("status", "any");
        p.put("context", "edit");
        p.put("per_page", "1");
        p.put("_fields", CAMPOS);
        PaginaWp pagina = get(p);
        if (pagina.semId() > 0) {
            // ruling da Task 3: o Django da 500; aqui e "nao deu para perguntar", nunca "nao existe"
            LOG.warn("wordpress_api_erro: post do WordPress sem id numerico (slug={})", slug);
            throw new WordPressIndisponivel(null);
        }
        List<PostWp> posts = pagina.posts();
        return posts.isEmpty() ? Optional.empty() : Optional.of(posts.get(0));
    }

    /** status=any: sem ele a REST API so devolve publish e a reconciliacao nao veria rascunho, lixeira nem agendado. */
    public PaginaWp listar(int pagina) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("page", String.valueOf(pagina));
        p.put("per_page", String.valueOf(POR_PAGINA_MAX));
        p.put("status", "any");
        p.put("context", "edit");
        p.put("_fields", CAMPOS);
        return get(p);
    }

    private PaginaWp get(Map<String, String> params) {
        if (!configurado()) throw new WordPressIndisponivel(null);
        String query = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String credencial = usuario + ":" + senha.replace(" ", "");
        try {
            // URL malformada em WORDPRESS_API_URL: IllegalArgumentException, tambem "indisponivel" (nao 500)
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + CAMINHO + "?" + query)).timeout(TIMEOUT)
                    .header("Authorization", "Basic "
                            + Base64.getEncoder().encodeToString(credencial.getBytes(StandardCharsets.UTF_8)))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> r = Retentativa.executar(() -> enviar(req), 3, Retentativa.ESPERAS_PADRAO,
                    Retentativa::padrao, dormir);
            return interpretar(r);
        } catch (HttpStatusException | UncheckedIOException | IllegalStateException
                | IllegalArgumentException e) {
            LOG.warn("wordpress_api_erro: {}", e.getMessage());
            throw new WordPressIndisponivel(e);
        }
    }

    private HttpResponse<String> enviar(HttpRequest req) {
        long inicio = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            LOG.info("wordpress_api_request (caminho={}, status={}, ms={})", CAMINHO, r.statusCode(),
                    (System.nanoTime() - inicio) / 1_000_000);
            if (r.statusCode() >= 400) throw new HttpStatusException(r.statusCode());
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chamada ao WordPress interrompida", e);
        }
    }

    /** Corpo que nao e JSON (pagina de manutencao com 200) ou nao e lista e "indisponivel", nunca "zero posts". */
    private PaginaWp interpretar(HttpResponse<String> r) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(r.body());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Resposta do WordPress não é JSON.", e);
        }
        if (raiz == null || !raiz.isArray()) {
            throw new IllegalStateException("Resposta do WordPress não é uma lista de posts.");
        }
        List<PostWp> posts = new ArrayList<>();
        int semId = 0;
        for (JsonNode n : raiz) {
            // id fora de [0, Integer.MAX_VALUE] conta como sem id: o espelho guarda o id em int
            JsonNode id = n.path("id");
            if (n.isObject() && id.canConvertToLong() && id.asLong() >= 0 && id.asLong() <= Integer.MAX_VALUE) {
                posts.add(post(n));
            }
            else semId++;
        }
        int total = r.headers().firstValue("X-WP-TotalPages").map(ClienteWordPress::inteiro).orElse(1);
        return new PaginaWp(posts, total, semId);
    }

    private static int inteiro(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static PostWp post(JsonNode n) {
        JsonNode titulo = n.path("title");
        return new PostWp(n.get("id").asLong(), texto(n.get("slug")), texto(titulo.get("raw")),
                texto(titulo.get("rendered")), texto(n.get("status")), texto(n.get("date_gmt")),
                texto(n.get("link")), verdadeiro(n.get("password")));
    }

    /** null e ausente viram null; o resto, o str() do Python. */
    private static String texto(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }

    /** bool() do Python sobre o JSON: null, false, 0, "" e vazio sao falsos. */
    private static boolean verdadeiro(JsonNode n) {
        if (n == null || n.isNull()) return false;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) return n.asDouble() != 0;
        if (n.isString()) return !n.asString().isEmpty();
        return !n.isEmpty();
    }
}
