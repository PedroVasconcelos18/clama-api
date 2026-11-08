package me.clama.api.pedido.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
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
 * consent_ip = 1o item do X-Forwarded-For (senao REMOTE_ADDR). No Django um valor que nao e IP vira 500 do Postgres
 * (cast para inet); aqui o pedido e criado com consent_ip nulo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class XffInvalidoTest {
    static final String PEDIDO = """
            {"nome": "Maria Silva", "email": "maria@exemplo.com", "cpf_cnpj": "529.982.247-25", "valor_centavos": 3000,
             "consent_aceito": true, "turnstile_token": "tok"}""";

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

    ApiDePedidos api;

    @AfterEach
    void apagarPedidos() {
        ApiDePedidos.apagarPedidos(jdbc);
    }

    @BeforeEach
    void preparar() {
        limiter.limpar();
        ApiDePedidos.semearCatalogo(jdbc);
        api = new ApiDePedidos(porta);
    }

    String consentIp(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = (String) ApiDePedidos.JSON.readValue(r.body(), Map.class).get("id");
        return jdbc.queryForObject("select host(consent_ip) from orders_pedido where id = ?::uuid", String.class, id);
    }

    @Test
    void xffInvalidoNaoQuebra() throws Exception {
        assertThat(consentIp(api.post("/api/doacoes/", PEDIDO, null, "X-Forwarded-For", "lixo, 10.0.0.1"))).isNull();
        assertThat(consentIp(api.post("/api/doacoes/", PEDIDO, null, "X-Forwarded-For", "01.2.3.4"))).isNull();
        assertThat(consentIp(api.post("/api/doacoes/", PEDIDO, null, "X-Forwarded-For", "fe80::1%eth0"))).isNull();
        ApiDePedidos.Sessao s = api.entrar(usuarios, tokens,
                "xff-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com", false);
        assertThat(consentIp(api.post("/api/pedidos/", PEDIDO, s, "X-Forwarded-For", " , 203.0.113.9"))).isNull();
    }

    @Test
    void xffValidoOuRemoteAddr() throws Exception {
        assertThat(consentIp(api.post("/api/doacoes/", PEDIDO, null, "X-Forwarded-For", " 2001:db8::1 , 10.0.0.1")))
                .isEqualTo("2001:db8::1");
        assertThat(consentIp(api.post("/api/doacoes/", PEDIDO, null, "X-Forwarded-For", "203.0.113.9")))
                .isEqualTo("203.0.113.9");
        assertThat(consentIp(api.post("/api/doacoes/", PEDIDO, null))).isIn("127.0.0.1", "::1");
    }

    /** Django test_paywall.test_autenticado_admin_pode_criar_pedido: IsAuthenticated, admin nao e bloqueado e vira o dono. */
    @Test
    void adminAutenticadoPodeCriarPedido() throws Exception {
        String email = "adm-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        ApiDePedidos.Sessao s = api.entrar(usuarios, tokens, email, false);
        var admin = usuarios.buscarPorEmailIgnorandoCaixa(email).get(0);
        admin.setClamaAdmin(true);
        usuarios.saveAndFlush(admin);
        HttpResponse<String> r = api.post("/api/pedidos/", PEDIDO, s);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = (String) ApiDePedidos.JSON.readValue(r.body(), Map.class).get("id");
        assertThat(jdbc.queryForObject("select user_id from orders_pedido where id = ?::uuid", Long.class, id))
                .isEqualTo(admin.getId());
    }
}
