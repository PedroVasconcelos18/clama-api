package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
import me.clama.api.comum.fila.TipoDeJob;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cenarios gravados por clama-backend/contrato/test_gravar_freemium.py (etapa 5): submit do pedido gratuito, GET e
 * POST da confirmacao, o job confirmacao-freemium e a tarefa reconciliar-freemium. Turnstile em modo real contra o
 * stub; relogio congelado em todo cenario ("configuracao.relogio"); e-mails, jobs enfileirados, senhas temporarias e
 * o estado final de orders_pedido, users_user e das duas tabelas do freemium.
 */
// a "configuracao" gravada, inline (vence as variaveis do SO); a URL do stub, de porta aleatoria, vem do
// @DynamicPropertySource. A tarefa sintetica precisa de cron (AgendaConfig) e fica desligada no agendador.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "FRONTEND_URL=https://clama.me", "FRONTEND_BASE_URL=https://clama.me",
        "TURNSTILE_SECRET_KEY=segredo-turnstile-contrato", "FREEMIUM_IP_BLACKLIST_WINDOW_HOURS=1",
        "FREEMIUM_IP_BLACKLIST_THRESHOLD=3", "clama.agenda.contrato-tarefa-sintetica.cron=0 0 0 * * *",
        "clama.agenda.contrato-tarefa-sintetica.modo=DESLIGADA"})
@Import({BancoDeTeste.class, RelogioDeTeste.class, ContratoFreemiumTest.TarefaSintetica.class})
@ActiveProfiles("test")
class ContratoFreemiumTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/freemium");
    /** Gravados (os .json da pasta). */
    static final int GRAVADOS = 38;
    /** Grupos ligados, por prefixo do arquivo. Cada task move o seu prefixo de PENDENTES para LIGADOS. */
    static final List<String> LIGADOS = List.of();
    /** Gravados mas ainda sem implementacao (Tasks 3 a 6). */
    static final List<String> PENDENTES = List.of("freemium_submit_", "freemium_confirmar_",
            "job_confirmacao_freemium_", "reconciliar_freemium_");
    static final List<String> TABELAS = List.of("users_user", "token_blacklist_outstandingtoken",
            "token_blacklist_blacklistedtoken", "plans_plan", "instituicoes_instituicao", "orders_pedido",
            "freemium_freemiumconfirmationtoken", "freemium_freemiumblacklist", "scheduled_tasks");
    static final String TURNSTILE_CAMINHO = "/turnstile/v0/siteverify";
    static final StubDeIntegracoes TURNSTILE = new StubDeIntegracoes("requisicoes ao Turnstile", Map.of());
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void integracoes(DynamicPropertyRegistry r) {
        r.add("TURNSTILE_VERIFY_URL", () -> TURNSTILE.url() + TURNSTILE_CAMINHO);
    }

    @AfterAll
    static void pararStub() {
        TURNSTILE.parar();
    }

    @Value("${local.server.port}")
    int porta;

    @Autowired
    ApplicationContext ctx;

    @Autowired
    CaixaDeSaidaEmMemoria caixa;

    @Autowired
    List<TipoDeJob<?>> jobs;

    @Autowired
    List<TarefaAgendada> tarefas;

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

    // allowZeroInvocations: todos pendentes ate a Task 3 ligar o 1o prefixo (a guarda de cenarios() segue valendo);
    // tirar quando LIGADOS deixar de ser vazio
    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("cenarios")
    void cenario(Path json) throws Exception {
        replay().executar(json, base());
    }

    /**
     * Tarefa agendada no harness: um cenario gravado de verdade (reconciliar_freemium_noop) com a tarefa trocada pela
     * sintetica. O resumo e o estado final das duas tabelas do freemium que o Django gravou batem com o semeado no
     * Java (token semeado fica como esta), no relogio gravado; resumo diferente, token gerado esperado literal e tarefa
     * sem registro falham.
     */
    @Test
    void tarefaSinteticaComparaResumoEEstadoFinal(@TempDir Path dir) throws Exception {
        ObjectNode c = (ObjectNode) JSON.readTree(CENARIOS.resolve("reconciliar_freemium_noop.json").toFile());
        c.put("tarefa", TarefaSintetica.NOME);
        replay().executar(escrever(dir, "igual", c), base());
        assertThat(TarefaSintetica.relogio).isEqualTo(Instant.parse("2026-11-20T15:00:00Z"));

        // a tarefa grava um token aleatorio e uma linha de blacklist: no estado final, <token> e <uuid>
        ObjectNode gera = c.deepCopy();
        String pedido = c.get("dados").get("freemium_freemiumconfirmationtoken").get(0).get("pedido_id").asString();
        tabela(gera, "freemium_freemiumconfirmationtoken").addObject().put("id", "<uuid>").put("pedido_id", pedido)
                .put("token", ReplayDeContrato.TOKEN).put("expires_at", "<ts>").putNull("used_at")
                .put("ip_origem", "203.0.113.9").put("device_hash", "").put("created_at", "<ts>");
        tabela(gera, "freemium_freemiumblacklist").addObject().put("id", "<uuid>").put("cpf_hash", "c".repeat(64))
                .put("email_hash", "e".repeat(64)).putNull("telefone_hash").put("device_hash", "fp-abc123")
                .put("ip_hash", "i".repeat(64)).put("created_at", "<ts>");
        TarefaSintetica.gerar = true;
        try {
            replay().executar(escrever(dir, "gera", gera), base());
            ObjectNode literal = gera.deepCopy();
            ((ObjectNode) tabela(literal, "freemium_freemiumconfirmationtoken").get(1)).put("token", "x".repeat(64));
            assertThatThrownBy(() -> replay().executar(escrever(dir, "literal", literal), base()))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("estado final de freemium_freemiumconfirmationtoken");
        } finally {
            TarefaSintetica.gerar = false;
        }

        ObjectNode resumo = c.deepCopy();
        ((ObjectNode) resumo.get("resumo")).put("n_reconciliados", 1);
        assertThatThrownBy(() -> replay().executar(escrever(dir, "resumo", resumo), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("resumo da tarefa");
        ObjectNode semRegistro = c.deepCopy().put("tarefa", "nao-existe");
        assertThatThrownBy(() -> replay().executar(escrever(dir, "sem-registro", semRegistro), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("nao registrada");
        assertThatThrownBy(() -> replayBase().comRelogio(i -> {}).executar(escrever(dir, "sem-tarefas", c), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("use comTarefas");
    }

    /**
     * Divergencia gravada do job de confirmacao: no Django a task recebe [pedido_id, token]; o job do Java, so o
     * pedido_id. Vale no "job" e nos "jobs_enfileirados" (ordem mantida); os outros tipos passam como estao.
     */
    @Test
    void argDoJobDeConfirmacaoESoOPedido() throws Exception {
        JsonNode job = JSON.readTree(CENARIOS.resolve("job_confirmacao_freemium_ok.json").toFile()).get("job");
        assertThat(ReplayDeContrato.argDoJava(job.get("tipo").asString(), job.get("arg")))
                .isEqualTo("a1a1a1a1-a1a1-4a1a-8a1a-000000000001");
        JsonNode submit = JSON.readTree(CENARIOS.resolve("freemium_submit_ok.json").toFile()).get("jobs_enfileirados");
        assertThat(ReplayDeContrato.argsDoJava(submit))
                .isEqualTo(JSON.readTree("[{\"tipo\": \"confirmacao-freemium\", \"arg\": \"<uuid>\"}]"));
        JsonNode outros = JSON.readTree("[{\"tipo\": \"gerar-oracao\", \"arg\": \"x\"}, {\"tipo\": \"alerta-admin\","
                + " \"arg\": \"y\"}]");
        assertThat(ReplayDeContrato.argsDoJava(outros)).isEqualTo(outros);
        assertThatThrownBy(() -> ReplayDeContrato.argDoJava("confirmacao-freemium", JSON.readTree("\"so-o-id\"")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("[pedido_id, token]");
    }

    /** Falha de rede gravada ({"falha": "conexao"}): o stub fecha a conexao e a requisicao entra na comparacao. */
    @Test
    void stubFechaConexaoNaFalhaDeRede() throws Exception {
        StubDeIntegracoes stub = new StubDeIntegracoes("requisicoes ao Turnstile", Map.of());
        try {
            stub.preparar(JSON.readTree("[{\"metodo\": \"POST\", \"caminho\": \"" + TURNSTILE_CAMINHO
                    + "\", \"falha\": \"conexao\"}, {\"metodo\": \"POST\", \"caminho\": \"" + TURNSTILE_CAMINHO
                    + "\", \"status\": 200, \"corpo\": {\"success\": true}}]"));
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(stub.url() + TURNSTILE_CAMINHO))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("secret=s&response=tok")).build();
            assertThatThrownBy(() -> http.send(req, HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(IOException.class);
            assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("{\"success\":true}");
            JsonNode esperadas = JSON.readTree("[{\"metodo\": \"POST\", \"caminho\": \"" + TURNSTILE_CAMINHO
                    + "\", \"corpo\": \"secret=s&response=tok\"}]");
            ArrayNode duas = ((ArrayNode) esperadas.deepCopy()).add(esperadas.get(0));
            stub.conferir(duas, "falha-de-rede");
            assertThatThrownBy(() -> stub.conferir(esperadas, "falha-de-rede")).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("requisicoes ao Turnstile");
        } finally {
            stub.parar();
        }
    }

    private static ArrayNode tabela(ObjectNode cenario, String nome) {
        return (ArrayNode) cenario.get("estado_final").get(nome).get("linhas");
    }

    private static Path escrever(Path dir, String nome, ObjectNode c) throws Exception {
        return Files.writeString(dir.resolve(nome + ".json"), c.toString());
    }

    private URI base() {
        return URI.create("http://localhost:" + porta);
    }

    private ReplayDeContrato replayBase() {
        return new ReplayDeContrato(ctx.getBean(JdbcTemplate.class), ctx.getBean(UsuarioRepository.class),
                ctx.getBean(ServicoDeTokens.class), ctx.getBean(RateLimiter.class), TABELAS)
                .comStubs(Map.of("turnstile", TURNSTILE), ctx.getEnvironment())
                .comOracao(caixa, jobs, ContratoOracaoTest.ganchos(ctx));
    }

    private ReplayDeContrato replay() {
        return replayBase().comRelogio(ctx.getBean(RelogioDeTeste.Fixavel.class)::fixar).comTarefas(tarefas);
    }

    /**
     * Tarefa so de teste, no papel do app: so roda em EXECUTAR e guarda o agora do Clock; com "gerar", grava um token
     * aleatorio para o pedido do 1o token e uma linha de blacklist, no agora do Clock. Resumo de reconciliacao vazio.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TarefaSintetica {
        static final String NOME = "contrato-tarefa-sintetica";
        static volatile boolean gerar;
        static volatile Instant relogio;

        @Bean
        TarefaAgendada tarefaSintetica(JdbcTemplate jdbc, Clock clock) {
            return new TarefaAgendada() {
                @Override
                public String nome() {
                    return NOME;
                }

                @Override
                public boolean alteraDados() {
                    return true;
                }

                @Override
                public ResultadoDaTarefa executar(ModoDeExecucao modo) {
                    if (modo != ModoDeExecucao.EXECUTAR) {
                        throw new IllegalStateException("o replay roda em EXECUTAR: " + modo);
                    }
                    relogio = clock.instant();
                    if (gerar) {
                        Timestamp agora = Timestamp.from(relogio);
                        UUID pedido = jdbc.queryForObject("select pedido_id from freemium_freemiumconfirmationtoken"
                                + " order by created_at limit 1", UUID.class);
                        jdbc.update("insert into freemium_freemiumconfirmationtoken (id, created_at, updated_at,"
                                + " token, pedido_id, expires_at, used_at, ip_origem, device_hash) values (?, ?, ?, ?,"
                                + " ?, ?, null, '203.0.113.9'::inet, '')", UUID.randomUUID(), agora, agora,
                                UUID.randomUUID().toString().replace("-", "").repeat(2), pedido, agora);
                        jdbc.update("insert into freemium_freemiumblacklist (id, created_at, updated_at, cpf_hash,"
                                + " email_hash, telefone_hash, device_hash, ip_hash) values (?, ?, ?, ?, ?, null,"
                                + " 'fp-abc123', ?)", UUID.randomUUID(), agora, agora, "c".repeat(64), "e".repeat(64),
                                "i".repeat(64));
                    }
                    return new ResultadoDaTarefa(NOME, modo, Map.of("n_reconciliados", 0, "n_tokens_deletados", 0));
                }
            };
        }
    }
}
