package me.clama.api.identidade.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.Jwt;
import me.clama.api.identidade.token.ParDeTokens;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"LIMITE_ANONIMO_ATIVO=true", "DJANGO_CORS_ALLOWED_ORIGINS=http://localhost:5173"})
@Import({BancoDeTeste.class, SegurancaTest.RotasDeTeste.class})
@ActiveProfiles("test")
class SegurancaTest {

    static final Map<String, Object> NAO_AUTENTICADO = Map.of("error", Map.of(
            "code", "not_authenticated",
            "message", "Authentication required",
            "pastoral_message", "Faça login pra continuar."));
    static final Map<String, Object> CSRF_INVALIDO = Map.of("error", Map.of(
            "code", "csrf_invalido",
            "message", "CSRF verification failed",
            "pastoral_message", "Não conseguimos confirmar que este pedido veio de você. "
                    + "Recarregue a página e tente de novo."));

    static final Map<String, Object> SEM_PERMISSAO = Map.of("detail", "Você não tem permissão para executar essa ação.");

    /** Rotas que nao estao em lista nenhuma: a protecao tem que vir so do prefixo. */
    @RestController
    static class RotasDeTeste {
        @GetMapping({"/api/admin/teste/", "/api/outra/teste/"})
        Map<String, String> ok() {
            return Map.of("ok", "sim");
        }
    }

    @Value("${local.server.port}")
    int porta;

    @Autowired
    ServicoDeTokens tokens;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    Jwt jwt;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    HttpResponse<String> chamar(String metodo, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + path))
                .method(metodo, metodo.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}"));
        b.header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    Object corpo(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    Usuario usuario() {
        return usuario(false);
    }

    Usuario usuario(boolean admin) {
        Usuario u = new Usuario();
        u.setEmail(UUID.randomUUID() + "@x.com");
        u.setPassword("x");
        u.setClamaAdmin(admin);
        return usuarios.saveAndFlush(u);
    }

    String access() {
        return tokens.emitirPar(usuario()).access();
    }

    @Test
    void rotaAdminNovaNasceIsClamaAdmin() throws Exception {
        HttpResponse<String> anonimo = chamar("GET", "/api/admin/teste/");
        assertThat(anonimo.statusCode()).isEqualTo(401);
        assertThat(corpo(anonimo)).isEqualTo(NAO_AUTENTICADO);
        HttpResponse<String> cliente = chamar("GET", "/api/admin/teste/", "Cookie", "clama_access=" + access());
        assertThat(cliente.statusCode()).isEqualTo(403);
        assertThat(corpo(cliente)).isEqualTo(SEM_PERMISSAO);
        String admin = "clama_access=" + tokens.emitirPar(usuario(true)).access();
        assertThat(chamar("GET", "/api/admin/teste/", "Cookie", admin).statusCode()).isEqualTo(200);
    }

    @Test
    void rotaForaDeAdminECustomerNegadaMesmoAutenticado() throws Exception {
        assertThat(chamar("GET", "/api/outra/teste/").statusCode()).isEqualTo(401);
        String admin = "clama_access=" + tokens.emitirPar(usuario(true)).access();
        HttpResponse<String> r = chamar("GET", "/api/outra/teste/", "Cookie", admin);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(corpo(r)).isEqualTo(SEM_PERMISSAO);
    }

    @Test
    void urlDesconhecidaDa404ComoODjango() throws Exception {
        String admin = "clama_access=" + tokens.emitirPar(usuario(true)).access();
        for (String path : List.of("/api/admin/nao-existe/", "/api/customer/nao-existe/", "/api/nao-existe/")) {
            assertThat(chamar("GET", path).statusCode()).as(path).isEqualTo(404);
            assertThat(chamar("GET", path, "Cookie", admin).statusCode()).as(path).isEqualTo(404);
        }
        // metodo errado numa rota que existe nao conta como "sem rota": anonimo continua barrado
        assertThat(chamar("GET", "/api/customer/auth/logout/").statusCode()).isEqualTo(401);
    }

    /** GET /api/csrf/ e devolve {token, cookie csrftoken}. */
    String[] csrf() throws Exception {
        HttpResponse<String> r = chamar("GET", "/api/csrf/");
        String token = (String) ((Map<?, ?>) corpo(r)).get("csrf_token");
        String cookie = r.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        return new String[] {token, cookie};
    }

    @Test
    void csrfDevolveTokenECookie() throws Exception {
        HttpResponse<String> r = chamar("GET", "/api/csrf/");
        assertThat(r.statusCode()).isEqualTo(200);
        String token = (String) ((Map<?, ?>) corpo(r)).get("csrf_token");
        assertThat(token).matches("[a-zA-Z0-9]{32}"); // formato do Django: o rollback aceita o cookie
        List<String> cookies = r.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.getFirst()).startsWith("csrftoken=" + token + ";")
                .contains("Path=/api", "HttpOnly", "SameSite=Lax", "Max-Age=31449600")
                .doesNotContain("Secure");
    }

    @Test
    void csrfComCookieExistenteDevolveOMesmoTokenERegravaCookie() throws Exception {
        String[] c = csrf();
        HttpResponse<String> r = chamar("GET", "/api/csrf/", "Cookie", c[1]);
        assertThat(((Map<?, ?>) corpo(r)).get("csrf_token")).isEqualTo(c[0]);
        assertThat(r.headers().firstValue("Set-Cookie").orElseThrow()).startsWith(c[1] + ";");
    }

    @Test
    void accessInvalidoDa401Pastoral() throws Exception {
        HttpResponse<String> r = chamar("GET", "/api/customer/me/", "Cookie", "clama_access=lixo");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(corpo(r)).isEqualTo(NAO_AUTENTICADO);
        // a autenticacao vem antes do CSRF: POST sem token com cookie invalido da 401, nao 403
        HttpResponse<String> post = chamar("POST", "/api/customer/auth/logout/", "Cookie", "clama_access=lixo");
        assertThat(post.statusCode()).isEqualTo(401);
        assertThat(corpo(post)).isEqualTo(NAO_AUTENTICADO);
    }

    @Test
    void anonimoEmRotaAutenticadaDa401Pastoral() throws Exception {
        HttpResponse<String> r = chamar("GET", "/api/customer/me/");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(corpo(r)).isEqualTo(NAO_AUTENTICADO);
    }

    @Test
    void usuarioInativoOuApagadoDa401() throws Exception {
        Usuario u = usuario();
        ParDeTokens p = tokens.emitirPar(u);
        u.setActive(false);
        usuarios.saveAndFlush(u);
        HttpResponse<String> r = chamar("GET", "/api/customer/me/", "Cookie", "clama_access=" + p.access());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(corpo(r)).isEqualTo(NAO_AUTENTICADO);

        // usuario inexistente
        long agora = Instant.now().getEpochSecond();
        String fantasma = jwt.assinar(Map.of("token_type", "access", "exp", agora + 600, "iat", agora,
                "jti", "f", "user_id", "999999999"));
        assertThat(chamar("GET", "/api/customer/me/", "Cookie", "clama_access=" + fantasma).statusCode())
                .isEqualTo(401);
    }

    @Test
    void getAutenticadoPassa() throws Exception {
        HttpResponse<String> r = chamar("GET", "/api/customer/me/", "Cookie", "clama_access=" + access());
        assertThat(r.statusCode()).isEqualTo(200);
    }

    @Test
    void postAutenticadoSemCsrfDa403() throws Exception {
        String a = access();
        for (String metodo : List.of("POST", "PATCH", "PUT", "DELETE")) {
            HttpResponse<String> r = chamar(metodo, "/api/customer/me/", "Cookie", "clama_access=" + a);
            assertThat(r.statusCode()).as(metodo).isEqualTo(403);
            assertThat(corpo(r)).isEqualTo(CSRF_INVALIDO);
        }
    }

    @Test
    void tokenDoCsrfEcoadoNoHeaderValida() throws Exception {
        String[] c = csrf();
        String cookies = "clama_access=" + access() + "; " + c[1];
        assertThat(chamar("PATCH", "/api/customer/me/", "Cookie", cookies, "X-CSRFToken", c[0]).statusCode())
                .isEqualTo(200);
        // token errado, ou sem o cookie csrftoken
        assertThat(chamar("PATCH", "/api/customer/me/", "Cookie", cookies, "X-CSRFToken", c[0] + "x").statusCode())
                .isEqualTo(403);
        assertThat(chamar("PATCH", "/api/customer/me/", "Cookie", cookies.split(";")[0], "X-CSRFToken", c[0])
                .statusCode()).isEqualTo(403);
    }

    @Test
    void csrfAceitaTokenMascaradoDoDjangoETrocaCookieForaDoFormato() throws Exception {
        // aba aberta antes da troca: cookie e token mascarado vindos do Django
        String cookies = "clama_access=" + access() + "; csrftoken=" + CsrfDjangoTest.SEGREDO;
        assertThat(chamar("PATCH", "/api/customer/me/", "Cookie", cookies, "X-CSRFToken", CsrfDjangoTest.MASCARADO)
                .statusCode()).isEqualTo(200);
        // cookie UUID (formato antigo do Java) nao vale e o /api/csrf/ grava um novo
        String uuid = "csrftoken=" + UUID.randomUUID();
        HttpResponse<String> r = chamar("GET", "/api/csrf/", "Cookie", uuid);
        String novo = (String) ((Map<?, ?>) corpo(r)).get("csrf_token");
        assertThat(novo).matches("[a-zA-Z0-9]{32}");
        assertThat(r.headers().firstValue("Set-Cookie").orElseThrow()).startsWith("csrftoken=" + novo + ";");
    }

    @Test
    void csrfConfereOrigemComoODjango() throws Exception {
        String[] c = csrf();
        String cookies = "clama_access=" + access() + "; " + c[1];
        // mesma origem que o Host
        assertThat(chamar("POST", "/api/customer/auth/logout/", "Cookie", cookies, "X-CSRFToken", c[0],
                "Origin", "http://localhost:" + porta).statusCode()).isEqualTo(205);
        // CSRF_TRUSTED_ORIGINS (default de producao)
        assertThat(chamar("POST", "/api/customer/auth/logout/", "Cookie", cookies, "X-CSRFToken", c[0],
                "Origin", "https://clama.me").statusCode()).isEqualTo(205);
        HttpResponse<String> r = chamar("POST", "/api/customer/auth/logout/", "Cookie", cookies, "X-CSRFToken", c[0],
                "Origin", "https://evil.example");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(corpo(r)).isEqualTo(CSRF_INVALIDO);
        // https sem Origin exige Referer confiavel
        assertThat(chamar("POST", "/api/customer/auth/logout/", "Cookie", cookies, "X-CSRFToken", c[0],
                "X-Forwarded-Proto", "https").statusCode()).isEqualTo(403);
        assertThat(chamar("POST", "/api/customer/auth/logout/", "Cookie", cookies, "X-CSRFToken", c[0],
                "X-Forwarded-Proto", "https", "Referer", "https://www.clama.me/conta").statusCode()).isEqualTo(205);
    }

    @Test
    void postAnonimoNaoExigeCsrf() throws Exception {
        assertThat(chamar("POST", "/api/nao-existe/").statusCode()).isEqualTo(404);
        assertThat(chamar("POST", "/api/customer/auth/login/").statusCode()).isNotIn(401, 403);
    }

    @Test
    void limiteAnonimoDa429Pastoral() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertThat(chamar("GET", "/api/health/", "X-Forwarded-For", "203.0.113.7").statusCode()).isEqualTo(200);
        }
        HttpResponse<String> r = chamar("GET", "/api/health/", "X-Forwarded-For", "203.0.113.7");
        assertThat(r.statusCode()).isEqualTo(429);
        @SuppressWarnings("unchecked")
        Map<String, Object> erro = (Map<String, Object>) ((Map<?, ?>) corpo(r)).get("error");
        assertThat(erro.get("code")).isEqualTo("rate_limited");
        assertThat((String) erro.get("message")).matches("Too many requests\\. Retry after (59|60) seconds\\.");
        assertThat(erro.get("pastoral_message"))
                .isEqualTo("Você fez vários pedidos seguidos — espera um instante e tenta de novo, com calma.");
        // outro cliente atras do mesmo proxy segue livre
        assertThat(chamar("GET", "/api/health/", "X-Forwarded-For", "203.0.113.8, 10.0.0.1").statusCode())
                .isEqualTo(200);
    }

    @Test
    void anonimo404NaoGastaCota() throws Exception {
        // URL sem rota nao chega numa view do DRF, entao nao passa pelo throttle (nem no dispatch de /error)
        for (int i = 0; i < 60; i++) {
            assertThat(chamar("GET", "/api/nao-existe/", "X-Forwarded-For", "203.0.113.20").statusCode()).isEqualTo(404);
        }
        assertThat(chamar("GET", "/api/health/", "X-Forwarded-For", "203.0.113.20").statusCode()).isEqualTo(200);
    }

    @Test
    void corsSoParaOrigensPermitidas() throws Exception {
        HttpResponse<String> ok = chamar("GET", "/api/health/", "Origin", "http://localhost:5173");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        assertThat(ok.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");
        // origem fora da lista: o django-cors-headers so nao poe os headers, nao rejeita
        HttpResponse<String> fora = chamar("GET", "/api/health/", "Origin", "https://evil.example");
        assertThat(fora.statusCode()).isEqualTo(200);
        assertThat(fora.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        HttpResponse<String> preflight = chamar("OPTIONS", "/api/customer/me/", "Origin", "http://localhost:5173",
                "Access-Control-Request-Method", "PATCH", "Access-Control-Request-Headers", "content-type,x-csrftoken");
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse(""))
                .containsIgnoringCase("x-csrftoken");
    }

    @Test
    void cookiesDeAuthComOsAtributosDoDjango() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        new CookiesDeAuth(true).gravar(resp, new ParDeTokens("AAA", "RRR"));
        List<String> gravados = resp.getHeaders("Set-Cookie");
        assertThat(gravados).hasSize(2);
        assertThat(gravados.get(0)).startsWith("clama_access=AAA; Path=/api; Max-Age=900; Expires=")
                .endsWith("; Secure; HttpOnly; SameSite=Lax");
        assertThat(gravados.get(1)).startsWith("clama_refresh=RRR; Path=/api; Max-Age=604800; Expires=")
                .endsWith("; Secure; HttpOnly; SameSite=Lax");
        MockHttpServletResponse local = new MockHttpServletResponse();
        new CookiesDeAuth(false).gravar(local, new ParDeTokens("AAA", "RRR"));
        assertThat(local.getHeader("Set-Cookie")).doesNotContain("Secure");

        MockHttpServletResponse apagar = new MockHttpServletResponse();
        new CookiesDeAuth(true).apagar(apagar);
        // delete_cookie do Django: sem HttpOnly nem Secure, Max-Age=0, mesmo path
        assertThat(apagar.getHeaders("Set-Cookie")).containsExactly(
                "clama_access=; Path=/api; Max-Age=0; Expires=Thu, 1 Jan 1970 00:00:00 GMT; SameSite=Lax",
                "clama_refresh=; Path=/api; Max-Age=0; Expires=Thu, 1 Jan 1970 00:00:00 GMT; SameSite=Lax");
    }
}
