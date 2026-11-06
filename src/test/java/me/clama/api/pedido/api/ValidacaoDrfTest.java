package me.clama.api.pedido.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Corpos fora dos cenarios gravados (tipos errados, limites, UUID em outras formas, e-mails no limite do EmailValidator)
 * com as respostas do DRF real: src/test/resources/contrato/drf/validacao_pedidos.json, gravado por
 * clama-backend/contrato/test_gerar_validacao_pedidos.py (GRAVAR_CONTRATO=1). 201 confere so o status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class ValidacaoDrfTest {
    static final Path FIXTURE = Path.of("src/test/resources/contrato/drf/validacao_pedidos.json");

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

    static ApiDePedidos.Sessao sessao;
    ApiDePedidos api;

    static Stream<Named<JsonNode>> casos() throws Exception {
        JsonNode casos = ApiDePedidos.JSON.readTree(FIXTURE.toFile()).get("casos");
        return casos.valueStream().map(c -> Named.of(c.get("rota").asString() + " "
                + (c.has("corpo_cru") ? c.get("corpo_cru").asString() : abreviar(c.get("corpo").toString())), c));
    }

    static String abreviar(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    @AfterEach
    void apagarPedidos() {
        ApiDePedidos.apagarPedidos(jdbc);
    }

    @BeforeEach
    void preparar() throws Exception {
        limiter.limpar();
        ApiDePedidos.semearCatalogo(jdbc);
        api = new ApiDePedidos(porta);
        if (sessao == null) {
            sessao = api.entrar(usuarios, tokens, "drf-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com",
                    false);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casos")
    void comoODrf(JsonNode caso) throws Exception {
        boolean pedido = caso.get("rota").asString().equals("pedidos");
        String corpo = caso.has("corpo_cru") ? caso.get("corpo_cru").asString() : caso.get("corpo").toString();
        HttpResponse<String> r = api.post(pedido ? "/api/pedidos/" : "/api/doacoes/", corpo, pedido ? sessao : null,
                "X-Forwarded-For", "203.0.113.9");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(caso.get("status").asInt());
        if (caso.has("resposta")) {
            // como texto: a ordem dos campos (a do serializer) tambem conta
            assertThat(ApiDePedidos.JSON.readTree(r.body()).toString()).isEqualTo(caso.get("resposta").toString());
        }
    }
}
