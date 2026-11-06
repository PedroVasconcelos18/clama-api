package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Cenarios gravados por clama-backend/contrato/test_gravar_pedidos.py. Os de checkout falam com o Mercado Pago pelo
 * {@link StubDeIntegracoes}; todos comparam o estado final de orders_pedido. Cada cenario traz a "configuracao" com
 * que foi gravado e o replay recusa contexto diferente: checkout_valor_abaixo_minimo_422
 * (MERCADOPAGO_MIN_VALOR_CENTAVOS=5000) e checkout_sem_notification_url_http (BACKEND_PUBLIC_URL=http://api.clama.test)
 * rodam cada um no seu contexto, nas classes aninhadas.
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
    static final List<String> LIGADOS = List.of("pedidos_criar_", "doacoes_", "status_", "checkout_");
    /** Gravados mas ainda sem implementacao. */
    static final List<String> PENDENTES = List.of();
    /** Gravados com outra configuracao: rodam nas classes aninhadas, cada um no seu contexto. */
    static final String MINIMO_5000 = "checkout_valor_abaixo_minimo_422.json";
    static final String BACKEND_HTTP = "checkout_sem_notification_url_http.json";
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
    ApplicationContext ctx;

    static Stream<Named<Path>> cenarios() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            List<Path> lista = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            List<Path> rodam = lista.stream().filter(p -> grupo(p, LIGADOS))
                    .filter(p -> !List.of(MINIMO_5000, BACKEND_HTTP).contains(p.getFileName().toString())).toList();
            // guarda: 41 gravados = 39 aqui + 2 nas classes aninhadas; fonte vazia ou incompleta nao passa calada
            assertThat(lista).hasSize(41);
            assertThat(rodam).hasSize(39);
            return rodam.stream().map(p -> Named.of(p.getFileName().toString(), p));
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

    @ParameterizedTest(name = "{0}")
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

    /**
     * Cenario sintetico sem passos: o pedido semeado (nome e CPF cifrados) e o estado final. Valor igual ao semeado
     * passa, decifrando as colunas; 3001 falha, como falharia um app que gravasse o valor errado.
     */
    @Test
    void estadoFinalRecusaValorPersistidoDiferente(@TempDir Path dir) throws Exception {
        URI base = URI.create("http://localhost:" + porta);
        replay().executar(comEstadoFinal(dir, "igual", 3000), base);
        assertThatThrownBy(() -> replay().executar(comEstadoFinal(dir, "diferente", 3001), base))
                .isInstanceOf(AssertionError.class).hasMessageContaining("estado final de orders_pedido")
                .hasMessageContaining("3001");
    }

    private static Path comEstadoFinal(Path dir, String nome, int valorEsperado) throws Exception {
        CampoCriptografado cifra = new CampoCriptografado();
        String json = """
                {
                  "cenario": "%1$s",
                  "usuarios": [],
                  "dados": {
                    "plans_plan": [{"id": "55555555-5555-4555-8555-000000000001", "nome": "Pedido de Oração",
                      "valor_centavos": 2000, "descricao": "", "complexidade": "simples", "ordem": 1, "ativo": true,
                      "visivel": true}],
                    "orders_pedido": [{"created_at": "2026-11-01T13:00:00+00:00",
                      "updated_at": "2026-11-01T13:00:00+00:00", "id": "77777777-7777-4777-8777-000000000001",
                      "nome": "%2$s", "email": "%3$s", "telefone": "", "idade": null, "sexo": "", "pedido_oracao": "",
                      "oracao_gerada": "", "valor_centavos": 3000, "canal_entrega": "email",
                      "status": "aguardando_pagamento", "plano_id": "55555555-5555-4555-8555-000000000001",
                      "consent_aceito": true, "consent_aceito_at": null, "consent_ip": "203.0.113.9",
                      "consent_versao": "1", "last_error": "", "retry_count": 0, "whatsapp_delivered_at": null,
                      "whatsapp_message_id": "", "whatsapp_read_at": null, "cpf_cnpj": "%4$s", "user_id": null,
                      "eh_gratuito": false, "device_hash": "", "oracao_email_sent_at": null,
                      "provider_payment_id": null, "provider_checkout_url": null, "pix_qr_code": null,
                      "pix_qr_code_base64": null, "instituicao_id": null}]
                  },
                  "passos": [],
                  "estado_final": {"orders_pedido": {
                    "colunas": ["id", "nome", "cpf_cnpj", "valor_centavos", "user_email", "consent_ip", "created_at"],
                    "cifradas_decifradas": ["nome", "cpf_cnpj"],
                    "linhas": [{"id": "77777777-7777-4777-8777-000000000001", "nome": "Maria Silva",
                      "cpf_cnpj": "52998224725", "valor_centavos": %5$d, "user_email": null,
                      "consent_ip": "203.0.113.9", "created_at": "<ts>"}]
                  }}
                }
                """.formatted(nome, cifra.convertToDatabaseColumn("Maria Silva"),
                cifra.convertToDatabaseColumn("maria@exemplo.com"), cifra.convertToDatabaseColumn("52998224725"),
                valorEsperado);
        return Files.writeString(dir.resolve(nome + ".json"), json);
    }

    private ReplayDeContrato replay() {
        return replay(ctx);
    }

    static ReplayDeContrato replay(ApplicationContext c) {
        return new ReplayDeContrato(c.getBean(JdbcTemplate.class), c.getBean(UsuarioRepository.class),
                c.getBean(ServicoDeTokens.class), c.getBean(RateLimiter.class), TABELAS)
                .comStub(STUB, c.getEnvironment());
    }

    /** O contexto de cada classe aninhada herda tudo do externo e sobrescreve uma propriedade. */
    static void executarSozinho(ApplicationContext c, String arquivo) throws Exception {
        String porta = c.getEnvironment().getProperty("local.server.port");
        replay(c).executar(CENARIOS.resolve(arquivo), URI.create("http://localhost:" + porta));
    }

    @Nested
    @TestPropertySource(properties = "MERCADOPAGO_MIN_VALOR_CENTAVOS=5000")
    class ComMinimoDe5000 {
        @Autowired
        ApplicationContext c;

        @Test
        void valorAbaixoDoMinimo() throws Exception {
            executarSozinho(c, MINIMO_5000);
        }
    }

    @Nested
    @TestPropertySource(properties = "BACKEND_PUBLIC_URL=http://api.clama.test")
    class ComBackendHttp {
        @Autowired
        ApplicationContext c;

        @Test
        void semNotificationUrl() throws Exception {
            executarSozinho(c, BACKEND_HTTP);
        }
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
