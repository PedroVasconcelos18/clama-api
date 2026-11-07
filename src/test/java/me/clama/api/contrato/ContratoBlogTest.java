package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.blog.JanelaFixa;
import me.clama.api.blog.tarefas.ContadorDeDivergencia;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cenarios gravados por clama-backend/contrato/test_gravar_blog.py (etapa 6): comentarios e curtidas do widget,
 * /api/blog/comments/{id}/, o admin de comentarios e de banimentos, o webhook do WordPress, o job sincronizar-espelho
 * e as cinco tarefas do blog. A REST do WordPress e os GETs publicos dos monitores sao simulados nos stubs (as bases
 * apontam para eles); relogio congelado em todo cenario; Sentry, e-mails, jobs enfileirados e o estado final das quatro
 * tabelas do blog e de payments_webhookevento.
 *
 * <p>Gravados com outra "configuracao" (rodam cada um no seu contexto, em classes aninhadas, quando o grupo ligar):
 * {@link #OUTRA_CONFIGURACAO}.
 */
// a "configuracao" gravada, inline (vence as variaveis do SO); as URLs dos stubs, de porta aleatoria, vem do
// @DynamicPropertySource. A tarefa sintetica precisa de cron (AgendaConfig) e fica desligada no agendador.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "WORDPRESS_API_USER=clama-django-sync", "WORDPRESS_API_APP_PASSWORD=abcd EFGH ijkl MNOP qrst UVWX",
        "WORDPRESS_WEBHOOK_SECRET=segredo-wordpress-contrato", "ADMIN_ALERT_EMAIL=alertas@clama.me", "INDEXNOW_KEY=",
        "clama.agenda.contrato-blog-tarefa-sintetica.cron=0 0 0 * * *",
        "clama.agenda.contrato-blog-tarefa-sintetica.modo=DESLIGADA"})
@Import({BancoDeTeste.class, RelogioDeTeste.class, ContratoBlogTest.TarefaSintetica.class})
@ActiveProfiles("test")
class ContratoBlogTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/blog");
    /** Gravados (os .json da pasta). */
    static final int GRAVADOS = 74;
    /** Grupos ligados, por prefixo do arquivo. Cada task move o seu prefixo de PENDENTES para LIGADOS. */
    static final List<String> LIGADOS = List.of("blog_comentarios_", "blog_like_", "blog_comentario_id_",
            "blog_admin_", "blog_webhook_", "job_sincronizar_espelho_", "tarefa_reconciliar_espelho_",
            "tarefa_monitorar_seo_", "tarefa_verificar_urls_", "tarefa_alerta_comentarios_", "tarefa_purgar_ips_");
    /** Gravados mas ainda sem implementacao. */
    static final List<String> PENDENTES = List.of();
    /**
     * Gravados com outra configuracao: sem credencial do WordPress (WORDPRESS_API_USER e _APP_PASSWORD vazios), com
     * INDEXNOW_KEY=chave-indexnow-contrato e com ADMIN_ALERT_EMAIL vazio. O replay recusa o contexto padrao para eles.
     */
    static final List<String> OUTRA_CONFIGURACAO = List.of("blog_comentarios_sem_wordpress_configurado.json",
            "tarefa_reconciliar_espelho_sem_credencial.json", "tarefa_monitorar_seo_indexnow_divergente.json",
            "tarefa_monitorar_seo_indexnow_inacessivel.json", "tarefa_alerta_comentarios_sem_destinatario.json");
    /**
     * Os de {@link #OUTRA_CONFIGURACAO} que ja rodam, cada um na sua classe aninhada (contexto proprio). Todo cenario de
     * outra configuracao de um grupo ligado tem de estar aqui: nenhum fica filtrado em silencio.
     */
    static final List<String> ANINHADOS = List.of(SemWordPress.CENARIO, SemWordPress.TAREFA, ComIndexNow.DIVERGENTE,
            ComIndexNow.INACESSIVEL, SemDestinatario.CENARIO);
    /**
     * Rede caida no WordPress ("falha": "conexao", o stub fecha a conexao sem responder): o Django faz um GET por
     * tentativa do with_retry; o java.net.http.HttpClient repete sozinho o GET cuja conexao cai antes da resposta (sem
     * opcao por cliente), entao o Java manda o mesmo GET mais vezes. Divergencia aceita (opcao c): so nestes cenarios o
     * replay colapsa repeticoes seguidas identicas e exige mais requisicoes que o gravado; o resto compara inteiro.
     * Os dois monitores (um GET por URL, sem retentativa propria) mandam ate 5 GETs para a URL caida; o stub dos
     * monitores derruba os reenvios tambem ({@link StubDeIntegracoes#comQuedaPersistente}), e o resumo continua sendo o
     * nome da classe do requests ("ConnectionError").
     */
    static final List<String> REDE_CAIDA = List.of("blog_comentarios_criar_wordpress_indisponivel_400.json",
            "blog_comentarios_lista_sem_espelho_wp_fora.json", "blog_like_post_indisponivel.json",
            "tarefa_monitorar_seo_indisponivel.json", "tarefa_verificar_urls_regressao.json");
    static final List<String> TABELAS = List.of("users_user", "token_blacklist_outstandingtoken",
            "token_blacklist_blacklistedtoken", "blog_reacao", "blog_comentario", "blog_customerbanido",
            "blog_postespelho", "payments_webhookevento", "scheduled_tasks");
    static final StubDeIntegracoes WORDPRESS = new StubDeIntegracoes("requisicoes ao WordPress",
            Map.of("autorizacao", "Authorization", "accept", "Accept"));
    static final StubDeIntegracoes MONITORES = new StubDeIntegracoes("GETs dos monitores", Map.of())
            .comQuedaPersistente();
    static final TransportEmMemoria SENTRY = new TransportEmMemoria();
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void integracoes(DynamicPropertyRegistry r) {
        r.add("WORDPRESS_API_URL", WORDPRESS::url);
        r.add("FRONTEND_PUBLIC_BLOG_BASE_URL", MONITORES::url);
    }

    @AfterAll
    static void pararStubs() {
        WORDPRESS.parar();
        MONITORES.parar();
    }

    @BeforeEach
    void ligarSentry() {
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(SENTRY.fabrica());
        });
    }

    @AfterEach
    void desligarSentry() {
        Sentry.close();
    }

    @Value("${local.server.port}")
    int porta;

    @Autowired
    ApplicationContext ctx;

    @Autowired
    List<TarefaAgendada> tarefas;

    static Stream<Named<Path>> cenarios() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            List<Path> lista = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            List<Path> rodam = lista.stream().filter(p -> grupo(p, LIGADOS))
                    .filter(p -> !OUTRA_CONFIGURACAO.contains(p.getFileName().toString())).toList();
            // guarda: fonte vazia ou incompleta nao passa calada; o que nao roda esta pendente
            assertThat(lista).hasSize(GRAVADOS);
            assertThat(lista.stream().filter(p -> grupo(p, LIGADOS) || grupo(p, PENDENTES)).count())
                    .isEqualTo(GRAVADOS);
            return rodam.stream().map(p -> Named.of(p.getFileName().toString(), p));
        }
    }

    static boolean grupo(Path p, List<String> prefixos) {
        return prefixos.stream().anyMatch(p.getFileName().toString()::startsWith);
    }

    /**
     * Cenario gravado nao pode sumir sem aviso: ou esta ligado, ou esta pendente (em um so). Todo prefixo tem cenario
     * (um prefixo com erro de digitacao nao passa calado), e os de outra configuracao existem.
     */
    @Test
    void todoCenarioLigadoOuPendente() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            List<Path> lista = s.filter(p -> p.toString().endsWith(".json")).toList();
            assertThat(lista).hasSize(GRAVADOS)
                    .allSatisfy(p -> assertThat(grupo(p, LIGADOS)).as("%s", p).isNotEqualTo(grupo(p, PENDENTES)));
            Stream.concat(LIGADOS.stream(), PENDENTES.stream()).forEach(prefixo -> assertThat(lista)
                    .as("prefixo %s", prefixo).anyMatch(p -> p.getFileName().toString().startsWith(prefixo)));
            assertThat(lista).extracting(p -> p.getFileName().toString()).containsAll(OUTRA_CONFIGURACAO);
            assertThat(OUTRA_CONFIGURACAO.stream().filter(a -> grupo(Path.of(a), LIGADOS)).toList())
                    .as("outra configuracao de grupo ligado sem classe aninhada").isEqualTo(ANINHADOS);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cenarios")
    void cenario(Path json) throws Exception {
        replay().executar(json, base());
    }

    /**
     * A tolerancia de {@link #REDE_CAIDA} e so a repeticao: os tres cenarios existem e sao de grupo ligado; igual ao
     * gravado passa sem tolerar nada (o replay exige que algum stub tenha tolerado); um GET a mais que nao e repeticao
     * seguida (outro caminho) ou fora de ordem continua falhando.
     */
    @Test
    void redeCaidaSoToleraRepeticaoSeguida() throws Exception {
        assertThat(REDE_CAIDA).allSatisfy(a -> {
            assertThat(CENARIOS.resolve(a)).exists();
            assertThat(grupo(Path.of(a), LIGADOS)).isTrue();
        });
        StubDeIntegracoes stub = new StubDeIntegracoes("requisicoes ao WordPress", Map.of("accept", "Accept"));
        try {
            JsonNode gravadas = JSON.readTree("[{\"metodo\": \"GET\", \"caminho\": \"/a\", \"accept\": null},"
                    + " {\"metodo\": \"GET\", \"caminho\": \"/b\", \"accept\": null}]");
            HttpClient http = HttpClient.newHttpClient();
            for (String[] rodada : new String[][] {{"/a", "/a", "/b", "/b"}, {"/a", "/b"}, {"/a", "/b", "/a"},
                    {"/b", "/a", "/a"}}) {
                stub.preparar(JSON.createArrayNode());
                for (String caminho : rodada) {
                    http.send(HttpRequest.newBuilder(URI.create(stub.url() + caminho)).build(),
                            HttpResponse.BodyHandlers.discarding());
                }
                if (rodada.length == 4) {
                    assertThat(stub.conferirComRepeticoes(gravadas, "repetido")).isTrue(); // so repeticao seguida
                } else if (rodada.length == 2) {
                    assertThat(stub.conferirComRepeticoes(gravadas, "igual")).isFalse(); // igual ao gravado
                } else {
                    // GET a mais que nao repete o anterior, ordem trocada: falha
                    assertThatThrownBy(() -> stub.conferirComRepeticoes(gravadas, "x"))
                            .as(String.join(",", rodada)).isInstanceOf(AssertionError.class);
                }
            }
        } finally {
            stub.parar();
        }
    }

    /**
     * Queda persistente (stub dos monitores): os reenvios automaticos do HttpClient para a URL caida caem tambem, sem
     * consumir a resposta da proxima URL; a proxima requisicao diferente segue a fila. Sem ela, o reenvio levaria a
     * resposta seguinte.
     */
    @Test
    void quedaPersistenteDerrubaSoOReenvioIdentico() throws Exception {
        for (boolean persistente : new boolean[] {true, false}) {
            StubDeIntegracoes stub = new StubDeIntegracoes("monitores", Map.of());
            if (persistente) {
                stub.comQuedaPersistente();
            }
            try {
                stub.preparar(JSON.readTree("[{\"falha\": \"conexao\"}, {\"status\": 404, \"corpo_texto\": \"\"}]"));
                HttpClient http = HttpClient.newHttpClient();
                HttpRequest a = HttpRequest.newBuilder(URI.create(stub.url() + "/a")).build();
                if (persistente) {
                    assertThatThrownBy(() -> http.send(a, HttpResponse.BodyHandlers.discarding()))
                            .isInstanceOf(IOException.class);
                    assertThat(http.send(HttpRequest.newBuilder(URI.create(stub.url() + "/b")).build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
                    assertThat(stub.conferirComRepeticoes(JSON.readTree("[{\"metodo\": \"GET\", \"caminho\":"
                            + " \"/a\"}, {\"metodo\": \"GET\", \"caminho\": \"/b\"}]"), "persistente")).isTrue();
                } else {
                    // o reenvio do JDK pega o 404 que era da proxima URL
                    assertThat(http.send(a, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
                }
            } finally {
                stub.parar();
            }
        }
    }

    /** WORDPRESS_API_USER e _APP_PASSWORD vazios: slug sem espelho nao existe (404), sem rede. */
    @Nested
    @TestPropertySource(properties = {"WORDPRESS_API_USER=", "WORDPRESS_API_APP_PASSWORD="})
    class SemWordPress {
        static final String CENARIO = "blog_comentarios_sem_wordpress_configurado.json";
        static final String TAREFA = "tarefa_reconciliar_espelho_sem_credencial.json";

        @Autowired
        ApplicationContext c;

        @Test
        void semWordPressConfigurado() throws Exception {
            aninhado(c, CENARIO);
        }

        /** Reconciliacao sem credencial: abortada, sem rede e sem Sentry. */
        @Test
        void reconciliacaoSemCredencial() throws Exception {
            aninhado(c, TAREFA);
        }
    }

    /** INDEXNOW_KEY definida: o monitor de SEO faz a 4a checagem. */
    @Nested
    @TestPropertySource(properties = "INDEXNOW_KEY=chave-indexnow-contrato")
    class ComIndexNow {
        static final String DIVERGENTE = "tarefa_monitorar_seo_indexnow_divergente.json";
        static final String INACESSIVEL = "tarefa_monitorar_seo_indexnow_inacessivel.json";

        @Autowired
        ApplicationContext c;

        @Test
        void chaveDivergente() throws Exception {
            aninhado(c, DIVERGENTE);
        }

        @Test
        void chaveInacessivel() throws Exception {
            aninhado(c, INACESSIVEL);
        }
    }

    /** ADMIN_ALERT_EMAIL vazio: o alerta diario conta e nao envia. */
    @Nested
    @TestPropertySource(properties = "ADMIN_ALERT_EMAIL=")
    class SemDestinatario {
        static final String CENARIO = "tarefa_alerta_comentarios_sem_destinatario.json";

        @Autowired
        ApplicationContext c;

        @Test
        void alertaSemDestinatario() throws Exception {
            aninhado(c, CENARIO);
        }
    }

    private static void aninhado(ApplicationContext c, String cenario) throws Exception {
        replay(c).executar(CENARIOS.resolve(cenario),
                URI.create("http://localhost:" + c.getEnvironment().getProperty("local.server.port")));
    }

    /**
     * O harness da etapa 6 num cenario gravado de verdade (tarefa_monitorar_seo_saudavel, com uma linha em cada tabela
     * do blog e em payments_webhookevento), com a tarefa trocada pela sintetica, que faz os tres GETs dos monitores na
     * base configurada (o stub): os corpos de texto, as requisicoes, a base do Django no resumo e no Sentry, o estado
     * final das cinco tabelas (IP decifrado, datas deslocadas do relogio) e "tarefas" em sequencia.
     */
    @Test
    void tarefaSinteticaComparaMonitoresSentryEEstado(@TempDir Path dir) throws Exception {
        ObjectNode c = (ObjectNode) JSON.readTree(CENARIOS.resolve("tarefa_monitorar_seo_saudavel.json").toFile());
        c.put("tarefa", TarefaSintetica.NOME);
        replay().executar(escrever(dir, "igual", c), base());

        // Sentry com a URL do stub: compara com a base do Django
        TarefaSintetica.alertar = true;
        try {
            ObjectNode alerta = c.deepCopy();
            alerta.putArray("sentry").addObject().put("nivel", "error")
                    .put("mensagem", "SEO do domínio comprometido — https://clama.me/blog");
            replay().executar(escrever(dir, "alerta", alerta), base());
            assertThatThrownBy(() -> replay().executar(escrever(dir, "sem-alerta", c), base()))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("mensagens do Sentry");
        } finally {
            TarefaSintetica.alertar = false;
        }

        // duas rodadas em sequencia, cada uma com o seu resumo
        ObjectNode duas = c.deepCopy();
        duas.remove("tarefa");
        duas.remove("resumo");
        ArrayNode rodadas = duas.putArray("tarefas");
        rodadas.addObject().put("tarefa", TarefaSintetica.NOME).set("resumo", c.get("resumo"));
        rodadas.addObject().put("tarefa", TarefaSintetica.NOME).set("resumo", c.get("resumo"));
        ((ArrayNode) duas.get("monitores")).addAll((ArrayNode) c.get("monitores"));
        ((ArrayNode) duas.get("monitores_requisicoes")).addAll((ArrayNode) c.get("monitores_requisicoes"));
        replay().executar(escrever(dir, "duas", duas), base());

        // a base que o Django usou e a do resumo; outra nao bate
        ObjectNode outraBase = c.deepCopy().put("monitores_base", "https://outro.example");
        assertThatThrownBy(() -> replay().executar(escrever(dir, "outra-base", outraBase), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("resumo da tarefa");
        // o estado final compara o IP decifrado
        ObjectNode ip = c.deepCopy();
        ((ObjectNode) ip.get("estado_final").get("blog_comentario").get("linhas").get(0)).put("ip_address", "10.0.0.1");
        assertThatThrownBy(() -> replay().executar(escrever(dir, "ip", ip), base()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("estado final de blog_comentario");
        assertThatThrownBy(() -> replayBase().comRelogio(i -> {}).comTarefas(tarefas).executar(escrever(dir, "sem-sentry",
                c), base())).isInstanceOf(AssertionError.class).hasMessageContaining("use comSentry");
    }

    /**
     * Stub da REST do WordPress: grava caminho com a query crua, Authorization e Accept; serve headers e corpo que nao
     * e JSON.
     */
    @Test
    void stubServeHeadersETextoEGravaAuthorization() throws Exception {
        StubDeIntegracoes stub = new StubDeIntegracoes("requisicoes ao WordPress",
                Map.of("autorizacao", "Authorization", "accept", "Accept"));
        try {
            stub.preparar(JSON.readTree("[{\"status\": 200, \"corpo\": [{\"id\": 1}], \"headers\": {\"X-WP-TotalPages\":"
                    + " \"2\"}}, {\"status\": 301, \"corpo_texto\": \"\", \"headers\": {\"Location\": \"https://clama.me/x/\"}},"
                    + " {\"status\": 200, \"corpo_texto\": \"<html>Em manutenção</html>\", \"headers\": {\"Content-Type\":"
                    + " \"text/html; charset=utf-8\"}}]"));
            HttpClient http = HttpClient.newHttpClient();
            String caminho = "/wp-json/wp/v2/posts?slug=novo-post&_fields=id%2Cslug";
            HttpResponse<String> lista = http.send(HttpRequest.newBuilder(URI.create(stub.url() + caminho))
                    .header("Authorization", "Basic eDp5").header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(lista.headers().firstValue("X-WP-TotalPages")).hasValue("2");
            assertThat(lista.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(lista.body()).isEqualTo("[{\"id\":1}]");
            HttpResponse<String> redirect = http.send(HttpRequest.newBuilder(URI.create(stub.url() + "/x")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(redirect.statusCode()).isEqualTo(301);
            assertThat(redirect.headers().firstValue("Location")).hasValue("https://clama.me/x/");
            HttpResponse<String> html = http.send(HttpRequest.newBuilder(URI.create(stub.url() + "/y")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(html.headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
            assertThat(html.body()).isEqualTo("<html>Em manutenção</html>");
            stub.conferir(JSON.readTree("[{\"metodo\": \"GET\", \"caminho\": \"" + caminho + "\", \"autorizacao\":"
                    + " \"Basic eDp5\", \"accept\": \"application/json\"}, {\"metodo\": \"GET\", \"caminho\": \"/x\","
                    + " \"autorizacao\": null, \"accept\": null}, {\"metodo\": \"GET\", \"caminho\": \"/y\", \"autorizacao\":"
                    + " null, \"accept\": null}]"), "wordpress");
        } finally {
            stub.parar();
        }
    }

    /** A normalizacao do D14 e so do admin de comentarios: outro cenario ou status nao mexe, e chave faltando falha. */
    @Test
    void normalizacaoDoEspelhoTemEscopoEstreito() throws Exception {
        String lista = "{\"status\": 200, \"body\": {\"count\": 1, \"results\": [{\"id\": \"a\","
                + " \"post_espelho_slug\": \"s\", \"post_espelho_titulo\": \"t\", \"post_espelho_url\": \"u\"}]}}";
        ObjectNode admin = (ObjectNode) JSON.readTree(lista);
        semChavesDoEspelho("blog_admin_comentarios_lista.json", admin);
        assertThat((JsonNode) admin).isEqualTo(JSON.readTree("{\"status\": 200, \"body\": {\"count\": 1,"
                + " \"results\": [{\"id\": \"a\"}]}}"));
        for (String outro : new String[] {"blog_admin_banidos_lista.json", "blog_comentarios_lista.json"}) {
            ObjectNode intacto = (ObjectNode) JSON.readTree(lista);
            semChavesDoEspelho(outro, intacto);
            assertThat((JsonNode) intacto).isEqualTo(JSON.readTree(lista));
        }
        ObjectNode erro = (ObjectNode) JSON.readTree("{\"status\": 403, \"body\": {\"detail\": \"x\"}}");
        semChavesDoEspelho("blog_admin_comentarios_nao_admin_403.json", erro);
        assertThat(erro.get("body").has("detail")).isTrue();
        ObjectNode semChave = (ObjectNode) JSON.readTree("{\"status\": 200, \"body\": {\"results\": [{\"id\": \"a\"}]}}");
        assertThatThrownBy(() -> semChavesDoEspelho("blog_admin_comentarios_lista.json", semChave))
                .isInstanceOf(AssertionError.class);
    }

    /** R12: a message gravada como marcador vira o marcador no obtido (se existir); sem marcador, nada muda. */
    @Test
    void mensagemR12ComparaORestoDoCorpo() throws Exception {
        JsonNode esperado = JSON.readTree("{\"body\": {\"error\": {\"code\": \"wordpress_indisponivel\", \"message\": \""
                + ReplayDeContrato.MENSAGEM_R12 + "\", \"pastoral_message\": \"p\"}}}");
        ObjectNode obtido = (ObjectNode) JSON.readTree("{\"body\": {\"error\": {\"code\": \"wordpress_indisponivel\","
                + " \"message\": \"Texto fixo do Java\", \"pastoral_message\": \"p\"}}}");
        ReplayDeContrato.semMensagemR12(obtido, esperado);
        assertThat((JsonNode) obtido).isEqualTo(esperado);
        ObjectNode semMessage = (ObjectNode) JSON.readTree("{\"body\": {\"error\": {\"code\": \"wordpress_indisponivel\","
                + " \"pastoral_message\": \"p\"}}}");
        ReplayDeContrato.semMensagemR12(semMessage, esperado);
        assertThat((JsonNode) semMessage).isNotEqualTo(esperado);
        ObjectNode outro = (ObjectNode) JSON.readTree("{\"body\": {\"error\": {\"message\": \"x\"}}}");
        ReplayDeContrato.semMensagemR12(outro, JSON.readTree("{\"body\": {\"error\": {\"message\": \"y\"}}}"));
        assertThat(outro.get("body").get("error").get("message").asString()).isEqualTo("x");
    }

    private static Path escrever(Path dir, String nome, ObjectNode c) throws Exception {
        return Files.writeString(dir.resolve(nome + ".json"), c.toString());
    }

    private URI base() {
        return URI.create("http://localhost:" + porta);
    }

    private ReplayDeContrato replayBase() {
        return replayBase(ctx);
    }

    private static ReplayDeContrato replayBase(ApplicationContext c) {
        return new ReplayDeContrato(c.getBean(JdbcTemplate.class), c.getBean(UsuarioRepository.class),
                c.getBean(ServicoDeTokens.class), c.getBean(RateLimiter.class), TABELAS)
                .comJanelaFixa(c.getBean(JanelaFixa.class)).comLimpeza(c.getBean(ContadorDeDivergencia.class)::limpar)
                .comRepeticoesToleradas(REDE_CAIDA).comNormalizacao(ContratoBlogTest::semChavesDoEspelho)
                .comStubs(Map.of("wordpress", WORDPRESS, "monitores", MONITORES), c.getEnvironment())
                .comOracao(c.getBean(CaixaDeSaidaEmMemoria.class), jobs(c), ContratoOracaoTest.ganchos(c));
    }

    static final String ADMIN_COMENTARIOS = "blog_admin_comentarios_";
    static final List<String> CHAVES_DO_ESPELHO = List.of("post_espelho_slug", "post_espelho_titulo", "post_espelho_url");

    /**
     * D14: o admin de comentarios do Java tem tres chaves aditivas (post_espelho_*) que o Django nao tem. So nos
     * cenarios blog_admin_comentarios_* e so no corpo 200: exige que existam (strings nao vazias) e as tira antes de
     * comparar. Qualquer outro cenario ou chave extra continua falhando na comparacao exata.
     */
    static void semChavesDoEspelho(String arquivo, ObjectNode obtido) {
        if (!arquivo.startsWith(ADMIN_COMENTARIOS) || obtido.path("status").asInt() != 200) {
            return;
        }
        JsonNode corpo = obtido.path("body");
        if (!corpo.isObject()) {
            return;
        }
        // lista paginada ou item de comentario; outras respostas 200 do cenario (o login, por exemplo) ficam como estao
        List<JsonNode> itens = corpo.has("results") ? corpo.get("results").valueStream().toList()
                : corpo.has("customer_email") ? List.of(corpo) : List.of();
        for (JsonNode item : itens) {
            for (String chave : CHAVES_DO_ESPELHO) {
                assertThat(item.path(chave).asString("")).as("%s: %s", arquivo, chave).isNotEmpty();
            }
            ((ObjectNode) item).remove(CHAVES_DO_ESPELHO);
        }
    }

    private ReplayDeContrato replay() {
        return replay(ctx);
    }

    static ReplayDeContrato replay(ApplicationContext c) {
        return replayBase(c).comRelogio(c.getBean(RelogioDeTeste.Fixavel.class)::fixar)
                .comTarefas(c.getBeanProvider(TarefaAgendada.class).orderedStream().toList()).comSentry(SENTRY.eventos);
    }

    private static List<TipoDeJob<?>> jobs(ApplicationContext c) {
        List<TipoDeJob<?>> lista = new java.util.ArrayList<>();
        c.getBeansOfType(TipoDeJob.class).values().forEach(lista::add);
        return lista;
    }

    /**
     * Tarefa so de teste, no papel de um monitor: GET em /robots.txt, /sitemap.xml e /blog da base configurada (o
     * stub dos monitores); com "alertar", uma mensagem de nivel error com a URL do /blog. Resumo de monitor saudavel.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TarefaSintetica {
        static final String NOME = "contrato-blog-tarefa-sintetica";
        static volatile boolean alertar;

        @Bean
        TarefaAgendada tarefaSinteticaBlog(Environment ambiente) {
            return new TarefaAgendada() {
                @Override
                public String nome() {
                    return NOME;
                }

                @Override
                public boolean alteraDados() {
                    return false;
                }

                @Override
                public ResultadoDaTarefa executar(ModoDeExecucao modo) {
                    String base = ambiente.getProperty("FRONTEND_PUBLIC_BLOG_BASE_URL");
                    HttpClient http = HttpClient.newHttpClient();
                    for (String caminho : List.of("/robots.txt", "/sitemap.xml", "/blog")) {
                        try {
                            http.send(HttpRequest.newBuilder(URI.create(base + caminho)).build(),
                                    HttpResponse.BodyHandlers.ofString());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    if (alertar) {
                        Sentry.captureMessage("SEO do domínio comprometido — " + base + "/blog", SentryLevel.ERROR);
                    }
                    return new ResultadoDaTarefa(NOME, modo, Map.of("base", base, "problemas", List.of(),
                            "verificacoes", 3));
                }
            };
        }
    }
}
