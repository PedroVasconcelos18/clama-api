package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cenarios gravados por clama-backend/contrato/test_gravar_pedidos.py. Os de checkout falam com o Mercado Pago pelo
 * {@link StubDeIntegracoes}. Os que trazem "configuracao" (checkout_valor_abaixo_minimo_422 e
 * checkout_sem_notification_url_http) so passam num contexto com aquelas propriedades; o replay recusa os outros.
 */
// propriedades inline vencem as variaveis do SO (nunca o token real do Mercado Pago nem a chave da Anthropic); as
// URLs do stub, de porta aleatoria, vem do @DynamicPropertySource, que vence ate as inline
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "MERCADOPAGO_ACCESS_TOKEN=test", "BACKEND_PUBLIC_URL=https://api.clama.test",
        "MERCADOPAGO_MIN_VALOR_CENTAVOS=1", "TURNSTILE_SECRET_KEY="})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class ContratoPedidosTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/pedidos");
    /** Grupos ligados, por prefixo do arquivo. Cada task move o seu prefixo de PENDENTES para LIGADOS. */
    static final List<String> LIGADOS = List.of();
    /** Gravados mas ainda sem implementacao. Fica vazio na Task 5. */
    static final List<String> PENDENTES = List.of("pedidos_", "doacoes_", "status_", "checkout_");
    static final List<String> TABELAS = List.of("users_user", "token_blacklist_outstandingtoken",
            "token_blacklist_blacklistedtoken", "plans_plan", "instituicoes_instituicao", "orders_pedido");
    static final StubDeIntegracoes STUB = new StubDeIntegracoes();

    @DynamicPropertySource
    static void integracoes(DynamicPropertyRegistry r) {
        r.add("clama.mercadopago.url", STUB::url);
        r.add("TURNSTILE_VERIFY_URL", () -> STUB.url() + "/turnstile/v0/siteverify");
    }

    @AfterAll
    static void pararStub() {
        STUB.parar();
    }

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

    @Autowired
    Environment ambiente;

    static Stream<Named<Path>> cenarios() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            List<Path> lista = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            return lista.stream().filter(p -> grupo(p, LIGADOS)).map(p -> Named.of(p.getFileName().toString(), p));
        }
    }

    static boolean grupo(Path p, List<String> prefixos) {
        return prefixos.stream().anyMatch(p.getFileName().toString()::startsWith);
    }

    /** Cenario gravado nao pode sumir sem aviso: ou esta ligado, ou esta pendente (em um so). */
    @Test
    void todoCenarioLigadoOuPendente() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            assertThat(s.filter(p -> p.toString().endsWith(".json")).toList()).hasSize(41)
                    .allSatisfy(p -> assertThat(grupo(p, LIGADOS)).as("%s", p).isNotEqualTo(grupo(p, PENDENTES)));
        }
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("cenarios")
    void cenario(Path json) throws Exception {
        replay().executar(json, URI.create("http://localhost:" + porta));
    }

    /**
     * Cenario sintetico: o passo faz o papel do app e manda ao stub a requisicao do Mercado Pago. A gravada leva
     * 30.0; 30 passa (numero compara por valor, a chave de idempotencia nao entra), 30.01 falha.
     */
    @Test
    void stubRecusaRequisicaoDiferenteDaGravada(@TempDir Path dir) throws Exception {
        Path igual = sintetico(dir, "igual", "30");
        Path diferente = sintetico(dir, "diferente", "30.01");
        URI base = URI.create("http://localhost:" + porta);

        replay().executar(igual, base);
        assertThatThrownBy(() -> replay().executar(diferente, base)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("requisicoes ao Mercado Pago").hasMessageContaining("30.01");
    }

    private ReplayDeContrato replay() {
        return new ReplayDeContrato(jdbc, usuarios, tokens, limiter, TABELAS).comStub(STUB, ambiente);
    }

    private static Path sintetico(Path dir, String nome, String valor) throws Exception {
        String pix = """
                {"status": "pending", "payment_method_id": "pix"}""";
        String json = """
                {
                  "cenario": "%1$s",
                  "usuarios": [],
                  "dados": {},
                  "passos": [{
                    "request": {"method": "POST", "path": "%2$s/v1/payments",
                      "headers": {"Authorization": "Bearer test", "X-Idempotency-Key": "clama-pedido-%1$s"},
                      "body": {"transaction_amount": %3$s, "payment_method_id": "pix"}},
                    "response": {"status": 201, "body": %4$s, "cookies": []}
                  }],
                  "mercadopago": [{"metodo": "POST", "caminho": "/v1/payments", "status": 201, "corpo": %4$s}],
                  "mercadopago_requisicoes": [{"metodo": "POST", "caminho": "/v1/payments", "autorizacao": "Bearer test",
                    "corpo": {"transaction_amount": 30.0, "payment_method_id": "pix"}}]
                }
                """.formatted(nome, STUB.url(), valor, pix);
        return Files.writeString(dir.resolve(nome + ".json"), json);
    }
}
