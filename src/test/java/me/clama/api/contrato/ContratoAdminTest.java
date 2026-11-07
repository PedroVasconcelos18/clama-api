package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cenarios gravados por clama-backend/contrato/test_gravar_admin_leitura.py (etapa 4C): historico de pedidos do
 * cliente, esqueci minha senha, admin de pedidos (lista, detalhe, reenviar, marcar gratuito), metricas, admin de
 * clientes e previa de prompt. Anthropic em stub; e-mails na caixa de saida do perfil test; jobs enfileirados lidos de
 * scheduled_tasks. Cenario de metricas traz "configuracao.relogio": quem o ligar passa comRelogio no replay().
 */
// a "configuracao" gravada, inline (vence as variaveis do SO: nunca a chave real da Anthropic); a URL do stub, de
// porta aleatoria, vem do @DynamicPropertySource
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ANTHROPIC_API_KEY=sk-ant-contrato", "FRONTEND_URL=https://clama.me", "FRONTEND_BASE_URL=https://clama.me"})
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class ContratoAdminTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/admin");
    /** Gravados (os .json da pasta). */
    static final int GRAVADOS = 38;
    /** Grupos ligados, por prefixo do arquivo. Cada task move o seu prefixo de PENDENTES para LIGADOS. */
    static final List<String> LIGADOS = List.of("customer_pedidos_", "forgot_", "admin_pedidos_",
            "admin_pedido_detalhe", "metrics_", "admin_reenviar_", "admin_marcar_gratuito_", "admin_customers_", "admin_customer_",
            "prompt_preview_");
    /** Gravados mas ainda sem implementacao. */
    static final List<String> PENDENTES = List.<String>of();
    static final List<String> TABELAS = List.of("users_user", "token_blacklist_outstandingtoken",
            "token_blacklist_blacklistedtoken", "plans_plan", "instituicoes_instituicao", "orders_pedido",
            "prompts_prompttemplate", "documents_documentocontexto", "instituicoes_repasse", "payments_webhookevento",
            "blog_postespelho", "blog_comentario", "blog_customerbanido", "scheduled_tasks");
    static final StubDeIntegracoes ANTHROPIC = new StubDeIntegracoes("requisicoes a Anthropic",
            Map.of("chave", "x-api-key", "versao", "anthropic-version", "beta", "anthropic-beta"));
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void integracoes(DynamicPropertyRegistry r) {
        r.add("clama.anthropic.url", ANTHROPIC::url);
    }

    @AfterAll
    static void pararStub() {
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
    void cenario(Path json, @TempDir Path dir) throws Exception {
        replay().comRelogio(ctx.getBean(RelogioDeTeste.Fixavel.class)::fixar).executar(buscaCorrigida(json, dir), base());
    }

    static final String CENARIO_Q = "admin_pedidos_q.json";

    /**
     * Divergencia aceita (Etapa 7, D1): o Django busca o `q` com ILIKE no texto cifrado e nunca acha nada ("Maria" da 0)
     * ou acha tudo ("gAAAA", o prefixo do token Fernet, da 5). O Java busca de verdade (nome/e-mail decifrados dos
     * pedidos sem conta, conta do usuario, prefixo do id). So neste arquivo e so nestes dois passos o esperado e
     * reescrito: "Maria" acha o unico pedido Maria Silva (o item gravado no passo "gAAAA"), "gAAAA" nao acha nenhum. A
     * guarda exige o esperado gravado antes de trocar; qualquer outro passo e cenario compara exato.
     */
    static Path buscaCorrigida(Path json, Path dir) throws Exception {
        if (!json.getFileName().toString().equals(CENARIO_Q)) {
            return json;
        }
        ObjectNode c = (ObjectNode) JSON.readTree(Files.readString(json));
        JsonNode passos = c.get("passos");
        ObjectNode maria = passoDe(passos, "/api/admin/pedidos/?q=Maria");
        ObjectNode cifrado = passoDe(passos, "/api/admin/pedidos/?q=gAAAA");
        ObjectNode corpoMaria = (ObjectNode) maria.get("response").get("body");
        ObjectNode corpoCifrado = (ObjectNode) cifrado.get("response").get("body");
        assertThat(corpoMaria.get("count").asInt()).as("gravado: Django nao acha 'Maria'").isZero();
        assertThat(corpoCifrado.get("count").asInt()).as("gravado: Django acha o texto cifrado").isEqualTo(5);
        JsonNode mariaSilva = corpoCifrado.get("results").valueStream()
                .filter(r -> r.get("nome").asString().equals("Maria Silva")).findFirst().orElseThrow();
        corpoMaria.put("count", 1);
        ((tools.jackson.databind.node.ArrayNode) corpoMaria.get("results")).add(mariaSilva);
        corpoCifrado.put("count", 0);
        ((tools.jackson.databind.node.ArrayNode) corpoCifrado.get("results")).removeAll();
        return Files.writeString(dir.resolve(CENARIO_Q), c.toString());
    }

    private static ObjectNode passoDe(JsonNode passos, String caminho) {
        return (ObjectNode) passos.valueStream().filter(p -> p.get("request").get("path").asString().equals(caminho))
                .findFirst().orElseThrow();
    }

    /**
     * O que a 4C acrescentou ao harness: header de resposta gravado (ausente = null; diferente falha) e o relogio
     * congelado de "configuracao" (passa ao gancho; sem gancho, falha).
     */
    @Test
    void sinteticoHeadersERelogio(@TempDir Path dir) throws Exception {
        List<Instant> fixados = new ArrayList<>();
        replay().comRelogio(fixados::add).executar(sintetico(dir, "igual", c -> {}), base());
        assertThat(fixados).containsExactly(Instant.parse("2026-11-20T15:00:00Z"));

        assertThatThrownBy(() -> replay().executar(sintetico(dir, "sem-gancho", c -> {}), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("use comRelogio");
        assertThatThrownBy(() -> replay().comRelogio(i -> {}).executar(sintetico(dir, "header", c -> ((ObjectNode) c
                .get("passos").get(0).get("response").get("headers")).put("X-Contrato-Ausente", "presente")), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("presente");
    }

    /** O e-mail do esqueci minha senha: so a palavra que confere com o hash do destinatario vira o placeholder. */
    @Test
    void senhaTemporariaDoEmailViraPlaceholder() {
        SenhaDjango senhas = new SenhaDjango();
        String hash = senhas.encode("Abc23456789xyz");
        assertThat(ReplayDeContrato.semSenhaTemporaria("Senha: Abc23456789xyz\nabcdefghijkmnp $1", hash, senhas))
                .isEqualTo("Senha: " + ReplayDeContrato.SENHA_TEMPORARIA + "\nabcdefghijkmnp $1");
    }

    private URI base() {
        return URI.create("http://localhost:" + porta);
    }

    private ReplayDeContrato replay() {
        return new ReplayDeContrato(ctx.getBean(JdbcTemplate.class), ctx.getBean(UsuarioRepository.class),
                ctx.getBean(ServicoDeTokens.class), ctx.getBean(RateLimiter.class), TABELAS)
                .comStubs(Map.of("anthropic", ANTHROPIC), ctx.getEnvironment())
                .comOracao(caixa, jobs, ContratoOracaoTest.ganchos(ctx));
    }

    private static Path sintetico(Path dir, String nome, java.util.function.Consumer<ObjectNode> mudar)
            throws Exception {
        ObjectNode c = (ObjectNode) JSON.readTree("""
                {
                  "cenario": "%s",
                  "configuracao": {"ANTHROPIC_API_KEY": "sk-ant-contrato", "relogio": "2026-11-20T15:00:00+00:00"},
                  "usuarios": [],
                  "passos": [{
                    "request": {"method": "GET", "path": "/api/health/",
                      "headers_resposta": ["Content-Type", "X-Contrato-Ausente"]},
                    "response": {"status": 200,
                      "body": {"status": "ok", "version": "0.1.0", "timestamp": "<ts>", "database": "ok"},
                      "cookies": [], "headers": {"Content-Type": "application/json", "X-Contrato-Ausente": null}}
                  }]
                }
                """.formatted(nome));
        mudar.accept(c);
        return Files.writeString(dir.resolve(nome + ".json"), c.toString());
    }
}
