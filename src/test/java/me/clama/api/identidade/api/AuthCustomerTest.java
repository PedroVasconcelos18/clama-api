package me.clama.api.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.identidade.token.Jwt;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class AuthCustomerTest {
    static final String SENHA = "Clama-forte-2026";
    // gerado pelo Django (contrato/compat-cripto.json)
    static final String PBKDF2 = "pbkdf2_sha256$600000$EfdeacSwCntHslm40SZ9GI$S03hczpCzav3H3jmZk8fUrL6Em6LMEl0iihbG6HfFYY=";
    static final Map<String, Object> CREDENCIAIS_INVALIDAS = Map.of("error", Map.of("code", "invalid_credentials",
            "message", "Email or password invalid", "pastoral_message", "Email ou senha inválidos."));

    @Value("${local.server.port}")
    int porta;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    ServicoDeTokens tokens;

    @Autowired
    Jwt jwt;

    @Autowired
    RateLimiter limiter;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    final String sufixo = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void limpaLimites() {
        limiter.limpar();
    }

    HttpResponse<String> post(String path, String corpo, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + path))
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> login(String email, String senha, String... headers) throws Exception {
        return post("/api/customer/auth/login/", json.writeValueAsString(Map.of("email", email, "password", senha)),
                headers);
    }

    Object corpo(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    Usuario usuario(String email, boolean admin) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword(new SenhaDjango().encode(SENHA));
        u.setClamaAdmin(admin);
        return usuarios.saveAndFlush(u);
    }

    void inserirViaJdbc(String email, String hash) {
        jdbc.update("""
                insert into users_user (password, is_superuser, email, is_staff, is_active, date_joined, name,
                    is_clama_admin, nome_completo, force_change_password, email_hash, nome_format_blog, sexo)
                values (?, false, ?, false, true, now(), '', false, 'Ana', false, '', 'compacto', '')""", hash, email);
    }

    @Test
    void loginCustomerEmailCaseInsensitive() throws Exception {
        String email = "Ana-" + sufixo + "@Exemplo.com";
        usuario(email, false);
        HttpResponse<String> r = login(email.toLowerCase(), SENHA);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(((Map<?, ?>) ((Map<?, ?>) corpo(r)).get("user")).get("email")).isEqualTo(email);
        assertThat(r.headers().allValues("Set-Cookie")).hasSize(2);
    }

    @Test
    void loginCustomerEmailAmbiguoDa401() throws Exception {
        String hash = new SenhaDjango().encode(SENHA);
        inserirViaJdbc("Ana-" + sufixo + "@x.com", hash);
        inserirViaJdbc("ana-" + sufixo + "@x.com", hash);
        HttpResponse<String> r = login("ANA-" + sufixo + "@X.COM", SENHA);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(corpo(r)).isEqualTo(CREDENCIAIS_INVALIDAS);
        assertThat(r.headers().allValues("Set-Cookie")).isEmpty();
        // o Django cai no ModelBackend com o e-mail digitado (lookup exato): quem bate exato entra
        assertThat(login("ana-" + sufixo + "@x.com", SENHA).statusCode()).isEqualTo(200);
    }

    @Test
    void loginComPbkdf2RegravaEmArgon2() throws Exception {
        String email = "pbkdf2-" + sufixo + "@x.com";
        inserirViaJdbc(email, PBKDF2);
        assertThat(login(email, SENHA).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select password from users_user where email = ?", String.class, email))
                .startsWith("argon2$");
    }

    @Test
    void loginComAccessVencidoNoCookieFunciona() throws Exception {
        long agora = Instant.now().getEpochSecond();
        String vencido = "clama_access=" + jwt.assinar(Map.of("token_type", "access", "exp", agora - 60,
                "iat", agora - 960, "jti", "abc", "user_id", "1"));
        String cliente = "cli-" + sufixo + "@x.com";
        String admin = "adm-" + sufixo + "@x.com";
        usuario(cliente, false);
        Usuario adm = usuario(admin, true);
        assertThat(login(cliente, SENHA, "Cookie", vencido).statusCode()).isEqualTo(200);
        assertThat(post("/api/admin/auth/login/",
                json.writeValueAsString(Map.of("email", admin, "password", SENHA)), "Cookie", vencido).statusCode())
                .isEqualTo(200);
        for (String rota : List.of("/api/customer/auth/refresh/", "/api/admin/auth/refresh/")) {
            String refresh = tokens.emitirPar(adm).refresh();
            assertThat(post(rota, "", "Cookie", vencido + "; clama_refresh=" + refresh).statusCode()).as(rota)
                    .isEqualTo(200);
        }
    }

    @Test
    void camposComoOCharFieldDoDrf() throws Exception {
        String email = "trim-" + sufixo + "@x.com";
        usuario(email, false);
        // trim_whitespace=True nos dois campos (o PasswordField do simplejwt herda do CharField)
        assertThat(login("  " + email + " ", " " + SENHA + "\t").statusCode()).isEqualTo(200);
        HttpResponse<String> r = post("/api/customer/auth/login/", "{\"email\": \"  \", \"password\": null}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(corpo(r)).isEqualTo(Map.of("email", List.of("Este campo pode não estar em branco."),
                "password", List.of("Este campo pode não ser nulo.")));
        r = post("/api/customer/auth/login/", "{\"email\": true, \"password\": [1]}");
        assertThat(corpo(r)).isEqualTo(Map.of("email", List.of("Não é uma string válida."),
                "password", List.of("Não é uma string válida.")));
        r = post("/api/customer/auth/login/", "[]");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(corpo(r)).isEqualTo(Map.of("non_field_errors",
                List.of("Dado inválido. Esperava um dicionário, mas recebeu list.")));
        r = post("/api/customer/auth/login/", "{");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat((String) ((Map<?, ?>) corpo(r)).get("detail")).startsWith("JSON parse error - ");
    }

    @Test
    void dataHoraComoODateTimeFieldDoDrf() {
        assertThat(UsuarioDtos.dataHora(OffsetDateTime.parse("2026-05-08T13:00:00Z")))
                .isEqualTo("2026-05-08T10:00:00-03:00");
        assertThat(UsuarioDtos.dataHora(OffsetDateTime.parse("2026-05-08T13:00:00.0405Z")))
                .isEqualTo("2026-05-08T10:00:00.040500-03:00");
    }

    @Test
    void loginCustomerLimitadoA5PorMinutoPorIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login("x@x.com", "errada", "X-Forwarded-For", "198.51.100.1").statusCode()).isEqualTo(401);
        }
        assertThat(login("x@x.com", "errada", "X-Forwarded-For", "198.51.100.1").statusCode()).isEqualTo(429);
        assertThat(post("/api/admin/auth/login/", "{}", "X-Forwarded-For", "198.51.100.1").statusCode())
                .isEqualTo(400);
        for (int i = 0; i < 5; i++) {
            post("/api/admin/auth/login/", "{}", "X-Forwarded-For", "198.51.100.2");
        }
        assertThat(post("/api/admin/auth/login/", "{}", "X-Forwarded-For", "198.51.100.2").statusCode())
                .isEqualTo(429);
    }

    @Test
    void trocaDeSenhaLimitadaA10PorHoraPorUsuario() throws Exception {
        Usuario u = usuario("troca-" + sufixo + "@x.com", false);
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + porta + "/api/csrf/")).build(), HttpResponse.BodyHandlers.ofString());
        String token = (String) ((Map<?, ?>) corpo(csrf)).get("csrf_token");
        String cookies = "clama_access=" + tokens.emitirPar(u).access() + "; csrftoken=" + token;
        String corpo = "{\"senha_atual\": \"errada\", \"nova_senha\": \"Outra-forte-2027\"}";
        for (int i = 0; i < 10; i++) {
            // IPs diferentes: a chave e o usuario
            assertThat(post("/api/customer/auth/change-password/", corpo, "Cookie", cookies, "X-CSRFToken", token,
                    "X-Forwarded-For", "203.0.113." + i).statusCode()).isEqualTo(400);
        }
        assertThat(post("/api/customer/auth/change-password/", corpo, "Cookie", cookies, "X-CSRFToken", token)
                .statusCode()).isEqualTo(429);
    }
}
