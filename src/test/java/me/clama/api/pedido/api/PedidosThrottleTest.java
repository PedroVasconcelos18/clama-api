package me.clama.api.pedido.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Throttles das views do Django (o contrato gravado nunca chega a eles): pedidos_create 10/min por usuario,
 * email_pedido 5/h pelo e-mail do usuario (minusculo), doacao_ip 10/h por IP/XFF ou usuario. Requisicao invalida
 * (400) tambem conta, porque o DRF checa o throttle antes de validar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class PedidosThrottleTest {
    @Value("${local.server.port}")
    int porta;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    ServicoDeTokens tokens;

    @Autowired
    RateLimiter limiter;

    final String sufixo = UUID.randomUUID().toString().substring(0, 8);
    ApiDePedidos api;

    @AfterEach
    void apagarPedidos() {
        ApiDePedidos.apagarPedidos(jdbc);
    }

    @BeforeEach
    void preparar() {
        limiter.limpar();
        api = new ApiDePedidos(porta);
    }

    @Test
    void pedidosLimitadosA5PorHoraPeloEmailEmMinusculas() throws Exception {
        // dois usuarios (pks diferentes) com o mesmo e-mail em caixas diferentes dividem a cota
        ApiDePedidos.Sessao maiusculo = api.entrar(usuarios, tokens, "Cota-" + sufixo + "@X.com", false);
        ApiDePedidos.Sessao minusculo = api.entrar(usuarios, tokens, "cota-" + sufixo + "@x.com", false);
        for (int i = 0; i < 3; i++) {
            assertThat(api.post("/api/pedidos/", "{}", maiusculo, "X-Forwarded-For", "203.0.113." + i).statusCode())
                    .isEqualTo(400);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(api.post("/api/pedidos/", "{}", minusculo).statusCode()).isEqualTo(400);
        }
        HttpResponse<String> r = api.post("/api/pedidos/", "{}", minusculo);
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.body()).contains("\"code\":\"rate_limited\"")
                .containsPattern("Retry after (3600|359\\d) seconds\\.");
        assertThat(r.headers().firstValue("Retry-After")).isEmpty();
    }

    @Test
    void pedidosLimitadosA10PorMinutoPorUsuario() throws Exception {
        // usuario sem e-mail: o EmailScopedThrottle nao se aplica e sobra so o pedidos_create
        jdbc.update("delete from users_user where email = ''");
        jdbc.update("""
                insert into users_user (password, is_superuser, email, is_staff, is_active, date_joined, name,
                    is_clama_admin, nome_completo, force_change_password, email_hash, nome_format_blog, sexo)
                values ('x', false, '', false, true, now(), '', false, 'Sem Email', false, '', 'compacto', '')""");
        Long id = jdbc.queryForObject("select id from users_user where email = ''", Long.class);
        ApiDePedidos.Sessao s = api.sessao(tokens.emitirPar(usuarios.findById(id).orElseThrow()).access());
        for (int i = 0; i < 10; i++) {
            // o escopo pedidos_create e dividido com /api/pedidos/gratuito/ (400 tambem conta nas duas)
            String rota = i < 4 ? "/api/pedidos/" : "/api/pedidos/gratuito/";
            assertThat(api.post(rota, "{}", s, "X-Forwarded-For", "198.51.100." + i).statusCode()).isEqualTo(400);
        }
        HttpResponse<String> r = api.post("/api/pedidos/gratuito/", "{}", s);
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.body()).containsPattern("Retry after (60|5\\d) seconds\\.");
        // o escopo pedidos_create e o mesmo: o POST seguinte em /api/pedidos/ tambem cai em 429
        assertThat(api.post("/api/pedidos/", "{}", s).statusCode()).isEqualTo(429);
    }

    @Test
    void doacoesLimitadasA10PorHoraPorIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(api.post("/api/doacoes/", "{}", null, "X-Forwarded-For", "203.0.113.70").statusCode())
                    .isEqualTo(400);
        }
        HttpResponse<String> r = api.post("/api/doacoes/", "{}", null, "X-Forwarded-For", "203.0.113.70");
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.body()).containsPattern("Retry after (3600|359\\d) seconds\\.");
        // a chave e o XFF inteiro (paridade: trocar o XFF burla o limite)
        assertThat(api.post("/api/doacoes/", "{}", null, "X-Forwarded-For", "203.0.113.70, 10.0.0.1").statusCode())
                .isEqualTo(400);
    }

    @Test
    void doacaoComCookieValidoLimitaPeloUsuario() throws Exception {
        ApiDePedidos.Sessao s = api.entrar(usuarios, tokens, "doador-" + sufixo + "@x.com", false);
        for (int i = 0; i < 10; i++) {
            assertThat(api.post("/api/doacoes/", "{}", s, "X-Forwarded-For", "192.0.2." + i).statusCode())
                    .isEqualTo(400);
        }
        assertThat(api.post("/api/doacoes/", "{}", s, "X-Forwarded-For", "192.0.2.99").statusCode()).isEqualTo(429);
    }

    @Test
    void anonimoESenhaTemporariaNaoGastamCota() throws Exception {
        ApiDePedidos.Sessao temporaria = api.entrar(usuarios, tokens, "temp-" + sufixo + "@x.com", true);
        for (int i = 0; i < 6; i++) {
            assertThat(api.post("/api/pedidos/", "{}", null).statusCode()).isEqualTo(401);
            assertThat(api.post("/api/pedidos/", "{}", temporaria).statusCode()).isEqualTo(403);
        }
    }

    /** Django test_rate_limit_blocks_after_60_requests (StatusPedidoView): 60 por minuto por IP, o 61o leva 429. */
    @Test
    void statusLimitadoA60PorMinutoPorIp() throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create("http://localhost:" + porta + "/api/pedidos/" + UUID.randomUUID() + "/"))
                .header("X-Forwarded-For", "198.51.100.200").build();
        for (int i = 0; i < 60; i++) {
            assertThat(http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
        assertThat(http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
    }
}
