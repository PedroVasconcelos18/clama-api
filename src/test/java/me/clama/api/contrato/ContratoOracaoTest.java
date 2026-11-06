package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import me.clama.api.oracao.AlertaDeErro;
import me.clama.api.oracao.SenhasTemporarias;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cenarios gravados por clama-backend/contrato/test_gravar_pagamento_oracao.py (etapa 4B): webhook do Mercado Pago,
 * pedido gratuito e os jobs gerar-oracao, enviar-oracao, boas-vindas e alerta-admin. Mercado Pago e Anthropic em stubs
 * separados; e-mails na caixa de saida do perfil test; jobs enfileirados lidos de scheduled_tasks (scheduler desligado).
 */
// a "configuracao" gravada, inline (vence as variaveis do SO: nunca a chave real da Anthropic); as URLs dos stubs, de
// porta aleatoria, vem do @DynamicPropertySource
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "MERCADOPAGO_ACCESS_TOKEN=test", "MERCADOPAGO_WEBHOOK_SECRET=segredo-webhook-contrato",
        "ANTHROPIC_API_KEY=sk-ant-contrato", "FRONTEND_URL=https://clama.me", "FRONTEND_BASE_URL=https://clama.me",
        "ADMIN_ALERT_EMAIL=alertas@clama.me", "TURNSTILE_SECRET_KEY="})
@Import({BancoDeTeste.class, ContratoOracaoTest.JobSintetico.class})
@ActiveProfiles("test")
class ContratoOracaoTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/oracao");
    /** Gravados (os .json da pasta). */
    static final int GRAVADOS = 37;
    /** Grupos ligados, por prefixo do arquivo. Cada task move o seu prefixo de PENDENTES para LIGADOS. */
    static final List<String> LIGADOS = List.of("job_gerar_", "job_enviar_", "job_alerta_");
    /** Gravados mas ainda sem implementacao. */
    static final List<String> PENDENTES = List.of("webhook_", "gratuito_", "job_boas_vindas_");
    static final List<String> TABELAS = List.of("users_user", "token_blacklist_outstandingtoken",
            "token_blacklist_blacklistedtoken", "plans_plan", "instituicoes_instituicao", "orders_pedido",
            "prompts_prompttemplate", "documents_documentocontexto", "instituicoes_repasse", "payments_webhookevento",
            "scheduled_tasks");
    static final StubDeIntegracoes MERCADO_PAGO = new StubDeIntegracoes();
    static final StubDeIntegracoes ANTHROPIC = new StubDeIntegracoes("requisicoes a Anthropic",
            Map.of("chave", "x-api-key", "versao", "anthropic-version", "beta", "anthropic-beta"));
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void integracoes(DynamicPropertyRegistry r) {
        r.add("clama.mercadopago.url", MERCADO_PAGO::url);
        r.add("clama.anthropic.url", ANTHROPIC::url);
        r.add("TURNSTILE_VERIFY_URL", () -> MERCADO_PAGO.url() + "/turnstile/v0/siteverify");
    }

    @AfterAll
    static void pararStubs() {
        MERCADO_PAGO.parar();
        ANTHROPIC.parar();
    }

    @Value("${local.server.port}")
    int porta;

    @Autowired
    ApplicationContext ctx;

    @Autowired
    CaixaDeSaidaEmMemoria caixa;

    @Autowired
    List<TipoDeJob<?>> jobs;

    static Stream<Named<Path>> cenarios() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            List<Path> lista = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            List<Path> rodam = lista.stream().filter(p -> grupo(p, LIGADOS)).toList();
            // guarda: fonte vazia ou incompleta nao passa calada; o que nao roda esta pendente
            assertThat(lista).hasSize(GRAVADOS);
            assertThat(rodam.size() + lista.stream().filter(p -> grupo(p, PENDENTES)).count()).isEqualTo(GRAVADOS);
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
            assertThat(s.filter(p -> p.toString().endsWith(".json")).toList()).hasSize(GRAVADOS)
                    .allSatisfy(p -> assertThat(grupo(p, LIGADOS)).as("%s", p).isNotEqualTo(grupo(p, PENDENTES)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cenarios")
    void cenario(Path json) throws Exception {
        replay().executar(json, base());
    }

    /**
     * Job sintetico com tudo o que o harness confere: requisicao a Anthropic, e-mail, job enfileirado e o estado final
     * (aqui, payments_webhookevento com payload jsonb). Igual ao gravado passa; cada diferenca falha.
     */
    @Test
    void jobSinteticoRecusaDiferencaDoGravado(@TempDir Path dir) throws Exception {
        replay().executar(sintetico(dir, "igual", c -> {}), base());

        assertThatThrownBy(() -> replay().executar(sintetico(dir, "anthropic", c -> ((ObjectNode) c
                .get("anthropic_requisicoes").get(0).get("corpo").get("messages").get(0)).put("content", "outra")),
                base())).isInstanceOf(AssertionError.class).hasMessageContaining("requisicoes a Anthropic")
                .hasMessageContaining("outra");
        assertThatThrownBy(() -> replay().executar(sintetico(dir, "beta", c -> ((ObjectNode) c
                .get("anthropic_requisicoes").get(0)).put("beta", "files-api-2025-04-14")), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("requisicoes a Anthropic");
        assertThatThrownBy(() -> replay().executar(sintetico(dir, "chave", c -> ((ObjectNode) c
                .get("anthropic_requisicoes").get(0)).put("chave", "sk-ant-outra")), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("requisicoes a Anthropic");
        assertThatThrownBy(() -> replay().executar(sintetico(dir, "email", c -> ((ObjectNode) c.get("emails").get(0))
                .put("texto", "Outro texto")), base())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("e-mails enviados");
        assertThatThrownBy(() -> replay().executar(sintetico(dir, "replyto", c -> ((ObjectNode) c.get("emails").get(0))
                .putArray("reply_to").add("outro@clama.me")), base())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("reply_to gravado");
        assertThatThrownBy(() -> replay().executar(sintetico(dir, "job", c -> ((ObjectNode) c
                .get("jobs_enfileirados").get(0)).put("arg", "ola-outro")), base())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("jobs enfileirados");
        assertThatThrownBy(() -> replay().executar(sintetico(dir, "sem-prefixo", c -> {
            ((ObjectNode) c.get("job")).put("arg", "sem-prefixo");
            ((ObjectNode) c.get("anthropic_requisicoes").get(0).get("corpo").get("messages").get(0))
                    .put("content", "sem-prefixo");
            ((ObjectNode) c.get("jobs_enfileirados").get(0)).put("arg", "sem-prefixo-eco");
        }), base())).isInstanceOf(AssertionError.class).hasMessageContaining("instancia do job");
        assertThatThrownBy(() -> replay().executar(sintetico(dir, "estado", c -> ((ObjectNode) c.get("estado_final")
                .get("payments_webhookevento").get("linhas").get(0).get("payload")).put("type", "merchant_order")),
                base())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("estado final de payments_webhookevento");
    }

    /**
     * Um cenario gravado de verdade com o job trocado pelo sintetico no-op: o estado final que o Django gravou (usuario
     * semeado com dados pessoais decifrados, hashes e o algoritmo da senha; as 4 tabelas) bate com o que o harness le
     * do banco do Java depois de semear o mesmo cenario.
     */
    @Test
    void estadoFinalGravadoBateComOSemeadoNoJava(@TempDir Path dir) throws Exception {
        ObjectNode c = (ObjectNode) JSON.readTree(CENARIOS.resolve("job_boas_vindas_sem_senha_noop.json").toFile());
        c.putObject("job").put("tipo", JobSintetico.NOME).put("arg", "noop");
        assertThat(c.get("estado_final").get("users_user").get("linhas")).hasSize(1);
        replay().executar(Files.writeString(dir.resolve("noop.json"), c.toString()), base());
    }

    private URI base() {
        return URI.create("http://localhost:" + porta);
    }

    private ReplayDeContrato replay() {
        return new ReplayDeContrato(ctx.getBean(JdbcTemplate.class), ctx.getBean(UsuarioRepository.class),
                ctx.getBean(ServicoDeTokens.class), ctx.getBean(RateLimiter.class), TABELAS)
                .comStubs(Map.of("mercadopago", MERCADO_PAGO, "anthropic", ANTHROPIC), ctx.getEnvironment())
                .comOracao(caixa, jobs, ganchos());
    }

    /** O cache de senhas temporarias e o dedup do alerta-admin do app (o cache.clear() do gravador). */
    private ReplayDeContrato.GanchosDeOracao ganchos() {
        SenhasTemporarias senhas = ctx.getBean(SenhasTemporarias.class);
        AlertaDeErro alerta = ctx.getBean(AlertaDeErro.class);
        return new ReplayDeContrato.GanchosDeOracao() {
            @Override
            public void limpar() {
                senhas.limpar();
                alerta.limpar();
            }

            @Override
            public void guardarSenha(String chave, String senha) {
                senhas.guardar(chave, senha);
            }

            @Override
            public boolean senhaExiste(String chave) {
                return senhas.ler(chave).isPresent();
            }
        };
    }

    private static Path sintetico(Path dir, String nome, java.util.function.Consumer<ObjectNode> mudar)
            throws Exception {
        ObjectNode c = (ObjectNode) JSON.readTree("""
                {
                  "cenario": "%1$s",
                  "usuarios": [],
                  "dados": {"payments_webhookevento": [{"id": "abababab-abab-4bab-8bab-000000000001",
                    "provider": "MERCADO_PAGO", "external_event_id": "117000000001", "event_type": "payment",
                    "payload": {"id": 117000000001, "type": "payment", "data": {"id": "1325814423"}},
                    "status": "IGNORADO", "pedido_id": null, "error_message": "tipo"}]},
                  "job": {"tipo": "%2$s", "arg": "ola"},
                  "anthropic": [{"metodo": "POST", "caminho": "/v1/messages", "status": 200,
                    "corpo": {"content": [{"type": "text", "text": "Amém"}]}}],
                  "anthropic_requisicoes": [{"metodo": "POST", "caminho": "/v1/messages", "chave": "sk-ant-contrato",
                    "versao": "2023-06-01",
                    "beta": null, "corpo": {"model": "claude-sonnet-4-6", "max_tokens": 1500,
                      "messages": [{"role": "user", "content": "ola"}]}}],
                  "emails": [{"para": "ana@exemplo.com", "assunto": "Sintetico", "texto": "Amém", "html": null,
                    "reply_to": ["contato@clama.me"]}],
                  "jobs_enfileirados": [{"tipo": "%2$s", "arg": "ola-eco"}],
                  "senhas_temporarias_final": [],
                  "estado_final": {"payments_webhookevento": {
                    "colunas": ["id", "provider", "payload", "status", "pedido_id", "created_at"],
                    "cifradas_decifradas": [],
                    "linhas": [{"id": "abababab-abab-4bab-8bab-000000000001", "provider": "MERCADO_PAGO",
                      "payload": {"id": "<id>", "type": "payment", "data": {"id": "1325814423"}},
                      "status": "IGNORADO", "pedido_id": null, "created_at": "<ts>"}]}}
                }
                """.formatted(nome, JobSintetico.NOME));
        mudar.accept(c);
        return Files.writeString(dir.resolve(nome + ".json"), c.toString());
    }

    /**
     * Job so de teste, no papel do app: pede a Anthropic (stub) o texto de "arg", manda o texto por e-mail e enfileira
     * "<arg>-eco" (instancia "<tipo>/<arg>-eco") numa transacao propria. "noop" nao faz nada.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class JobSintetico {
        static final String NOME = "contrato-sintetico";

        @Bean
        TipoDeJob<String> jobSintetico(Environment env, CaixaDeSaidaEmMemoria caixa, ObjectProvider<Fila> fila,
                TransactionTemplate tx) {
            return new TipoDeJob<>() {
                @Override
                public String nome() {
                    return NOME;
                }

                @Override
                public Class<String> payload() {
                    return String.class;
                }

                @Override
                public Duration esperaEntreTentativas() {
                    return Duration.ofSeconds(1);
                }

                @Override
                public void executar(String arg) throws Exception {
                    if (arg.equals("noop")) {
                        return;
                    }
                    String corpo = JSON.writeValueAsString(Map.of("model", "claude-sonnet-4-6", "max_tokens", 1500,
                            "messages", List.of(Map.of("role", "user", "content", arg))));
                    HttpResponse<String> resp = HttpClient.newHttpClient().send(HttpRequest
                            .newBuilder(URI.create(env.getProperty("clama.anthropic.url") + "/v1/messages"))
                            .header("x-api-key", env.getProperty("ANTHROPIC_API_KEY"))
                            .header("anthropic-version", "2023-06-01").header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    JsonNode json = JSON.readTree(resp.body());
                    caixa.enviar(new Email("ana@exemplo.com", "Sintetico", json.get("content").get(0).get("text")
                            .asString(), null));
                    // "sem-prefixo": instancia fora do formato <tipo>/<id>, que o harness tem de recusar
                    String instancia = (arg.equals("sem-prefixo") ? "" : NOME + "/") + arg + "-eco";
                    tx.executeWithoutResult(s -> fila.getObject().enfileirar(this, instancia, arg + "-eco"));
                }
            };
        }
    }
}
