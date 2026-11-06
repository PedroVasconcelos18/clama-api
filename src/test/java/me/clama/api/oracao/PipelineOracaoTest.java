package me.clama.api.oracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.email.EnviadorDeEmail;
import me.clama.api.comum.fila.Fila;
import me.clama.api.documento.DocumentoRepository;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import me.clama.api.prompt.PromptAtivo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O pipeline gerar -> enviar -> alerta com o db-scheduler de verdade (esperas curtas), a Claude num stub HTTP e o
 * caminho de falha final PRETENDIDO (o do Django e codigo morto: contexto-global da 4B). Nada disso foi gravado.
 */
@SpringBootTest(properties = {"clama.fila.scheduler-ativo=true", "ANTHROPIC_API_KEY=sk-ant-teste",
        "ADMIN_ALERT_EMAIL=alertas@clama.me", "FRONTEND_URL=https://clama.me",
        "clama.oracao.gerar-espera=100ms", "clama.oracao.enviar-espera=100ms", "clama.oracao.reagendamento=200ms"})
@Import({BancoDeTeste.class, PipelineOracaoTest.Config.class})
@ActiveProfiles("test")
@DirtiesContext // para o scheduler no fim: um contexto em cache roubaria os jobs dos outros testes
class PipelineOracaoTest {
    static final String ORACAO = "Senhor, acolhe a Maria.\n\nAmém.";
    static final AtomicInteger CHAMADAS = new AtomicInteger();
    /** Resposta da Claude por chamada (1-based): {status, corpo}. */
    static volatile IntFunction<Object[]> resposta = n -> ok();
    static volatile CountDownLatch segurar = new CountDownLatch(0);
    static final Set<String> ENVIO_FALHA_PARA = ConcurrentHashMap.newKeySet();
    static final AtomicInteger TENTATIVAS_DE_ENVIO_COM_FALHA = new AtomicInteger();
    static final HttpServer CLAUDE = iniciarStub();

    static Object[] ok() {
        return new Object[] {200, """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                 "content":[{"type":"text","text":"Senhor, acolhe a Maria.\\n\\nAmém."}],"stop_reason":"end_turn",
                 "stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""};
    }

    static Object[] sobrecarregada() {
        return new Object[] {529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"};
    }

    static Object[] semCreditos() {
        return new Object[] {400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                + "\"Your credit balance is too low to access the Anthropic API.\"}}"};
    }

    static HttpServer iniciarStub() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", t -> {
                t.getRequestBody().readAllBytes();
                Object[] r = resposta.apply(CHAMADAS.incrementAndGet());
                try {
                    segurar.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] corpo = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
                t.getResponseHeaders().add("Content-Type", "application/json");
                t.sendResponseHeaders((int) r[0], corpo.length);
                t.getResponseBody().write(corpo);
                t.close();
            });
            s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            s.start();
            return s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void claude(DynamicPropertyRegistry r) {
        r.add("clama.anthropic.url", () -> "http://127.0.0.1:" + CLAUDE.getAddress().getPort());
    }

    @AfterAll
    static void pararStub() {
        CLAUDE.stop(0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        /** O ClienteClaude real sem as esperas de 1 s e 2 s do retry do 529 (o resto igual). */
        @Bean
        @Primary
        ClienteClaude claudeSemEspera(@Value("${clama.anthropic.url}") String url, PromptAtivo prompts,
                DocumentoRepository documentos) {
            return new ClienteClaude("sk-ant-teste", url, false, prompts, documentos, Duration.ofSeconds(10), d -> {});
        }

        /** A Resend fora do ar para os destinatarios de ENVIO_FALHA_PARA; o resto vai para a caixa do perfil test. */
        @Bean
        @Primary
        EnviadorDeEmail enviadorQueFalha(CaixaDeSaidaEmMemoria caixa) {
            return e -> {
                if (ENVIO_FALHA_PARA.contains(e.para())) {
                    TENTATIVAS_DE_ENVIO_COM_FALHA.incrementAndGet();
                    throw new IllegalStateException("Resend fora do ar");
                }
                return caixa.enviar(e);
            };
        }
    }

    @Autowired Fila fila;
    @Autowired JobGerarOracao gerar;
    @Autowired JobEnviarOracao enviar;
    @Autowired AlertaDeErro alerta;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired CaixaDeSaidaEmMemoria caixa;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE scheduled_tasks, orders_pedido, plans_plan, prompts_prompttemplate,"
                + " documents_documentocontexto CASCADE");
        jdbc.update("INSERT INTO prompts_prompttemplate (id, created_at, updated_at, nome, versao, system_prompt,"
                + " instrucoes_por_complexidade, ativo) VALUES (?, now(), now(), 'P', 1, 'Você é o Clama.',"
                + " '{\"simples\": \"Escreva uma oração curta.\"}'::jsonb, true)", UUID.randomUUID());
        CHAMADAS.set(0);
        resposta = n -> ok();
        segurar = new CountDownLatch(0);
        ENVIO_FALHA_PARA.clear();
        TENTATIVAS_DE_ENVIO_COM_FALHA.set(0);
        caixa.limpar();
        alerta.limpar();
        alerta.relogio = Clock.systemUTC();
    }

    private UUID pedido(StatusPedido status, String oracao, int retryCount) {
        return tx.execute(s -> {
            Plano plano = new Plano();
            plano.setNome("Pedido de Oração");
            plano.setValorCentavos(2000);
            plano.setDescricao("");
            planos.save(plano);
            Pedido p = new Pedido();
            p.setNome("Maria Silva");
            p.setEmail("maria@exemplo.com");
            p.setPlano(plano);
            p.setValorCentavos(3000);
            p.setStatus(status);
            p.setOracaoGerada(oracao);
            p.setRetryCount(retryCount);
            return pedidos.save(p).getId();
        });
    }

    private Pedido ler(UUID id) {
        return pedidos.findById(id).orElseThrow();
    }

    private boolean enfileirarGerar(UUID id) {
        return tx.execute(s -> fila.enfileirar(gerar, JobGerarOracao.instancia(id), id));
    }

    private long emailsPara(String para) {
        return caixa.enviados().stream().filter(e -> e.para().equals(para)).count();
    }

    private int linhasNaFila() {
        return jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class);
    }

    /** Review Focus 2: webhook + reenvio (ou dois webhooks) = uma chamada a Claude e um e-mail. */
    @Test
    void gerarDuplicadoChamaClaudeUmaVez() throws Exception {
        UUID id = pedido(StatusPedido.PAGO, "", 0);
        segurar = new CountDownLatch(1); // a 1a chamada a Claude fica pendurada
        assertThat(enfileirarGerar(id)).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> CHAMADAS.get() == 1);

        assertThat(enfileirarGerar(id)).as("mesma instancia em execucao").isFalse();
        segurar.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> ler(id).getStatus() == StatusPedido.ENVIADA);

        assertThat(enfileirarGerar(id)).as("ja terminou: entra e cai no guard").isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> linhasNaFila() == 0);
        Thread.sleep(300);
        assertThat(CHAMADAS.get()).isEqualTo(1);
        assertThat(caixa.enviados()).hasSize(1);
        Email e = caixa.enviados().get(0);
        assertThat(e.para()).isEqualTo("maria@exemplo.com");
        assertThat(e.texto()).contains(ORACAO);
        assertThat(e.chaveDeIdempotencia()).isEqualTo("oracao/" + id);
        assertThat(ler(id).getOracaoGerada()).isEqualTo(ORACAO);
    }

    /**
     * Review Focus 3: o e-mail sai e o UPDATE status=enviada falha (trigger). A retentativa reenvia com a mesma chave;
     * a Resend (aqui a caixa do perfil test) responde "ja enviado" e o cliente recebe um e-mail so.
     */
    @Test
    void reenvioAposFalhaDeMarcacaoNaoDuplicaEmail() {
        UUID id = pedido(StatusPedido.ORACAO_GERADA, ORACAO, 0);
        jdbc.execute("CREATE SEQUENCE teste_falha_marcacao");
        jdbc.execute("""
                CREATE FUNCTION teste_falha_marcacao() RETURNS trigger AS $$ BEGIN
                  IF NEW.status = 'enviada' AND nextval('teste_falha_marcacao') = 1 THEN
                    RAISE EXCEPTION 'falha simulada ao marcar enviada';
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER teste_falha_marcacao BEFORE UPDATE ON orders_pedido FOR EACH ROW"
                + " EXECUTE FUNCTION teste_falha_marcacao()");
        try {
            tx.executeWithoutResult(s -> fila.enfileirar(enviar, JobEnviarOracao.instancia(id), id));
            await().atMost(Duration.ofSeconds(10)).until(() -> ler(id).getStatus() == StatusPedido.ENVIADA);
            assertThat(jdbc.queryForObject("select last_value from teste_falha_marcacao", Long.class))
                    .as("a 1a marcacao falhou de fato").isGreaterThanOrEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER teste_falha_marcacao ON orders_pedido");
            jdbc.execute("DROP FUNCTION teste_falha_marcacao()");
            jdbc.execute("DROP SEQUENCE teste_falha_marcacao");
        }
        assertThat(caixa.enviados()).hasSize(1);
        assertThat(caixa.enviados().get(0).chaveDeIdempotencia()).isEqualTo("oracao/" + id);
    }

    /** Review Focus 5a: 400 "credit balance" = erro/credit_balance direto, sem retry, com alerta. */
    @Test
    void creditosEsgotadosVaiParaErroComAlerta() throws Exception {
        resposta = n -> semCreditos();
        UUID id = pedido(StatusPedido.PAGO, "", 0);
        enfileirarGerar(id);
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsPara("alertas@clama.me") == 1);
        Thread.sleep(500); // nenhuma retentativa (a espera do gerar e 100 ms)

        Pedido p = ler(id);
        assertThat(p.getStatus()).isEqualTo(StatusPedido.ERRO);
        assertThat(p.getLastError()).isEqualTo("credit_balance");
        assertThat(p.getRetryCount()).isZero();
        assertThat(CHAMADAS.get()).isEqualTo(1);
        Email alertaEnviado = caixa.enviados().get(0);
        assertThat(alertaEnviado.assunto()).isEqualTo("[Clama] Pedido em ERRO - " + id.toString().substring(0, 8));
        assertThat(alertaEnviado.texto()).contains("Último Erro:\ncredit_balance\n")
                .contains("https://clama.me/admin/pedidos/" + id);
        assertThat(alertaEnviado.chaveDeIdempotencia()).isEqualTo("alerta/" + id + "/0");
        assertThat(caixa.enviados()).hasSize(1);
    }

    /**
     * Review Focus 5b: Claude fora do ar (529 esgotado em toda execucao). Cada rodada = 4 execucoes (3 retentativas de
     * 60 s, aqui 100 ms) x 3 tentativas do cliente; esgotada, aguardando_reenvio e a mesma instancia em 300 s (aqui
     * 200 ms), ate retry_count 3; a 4a rodada esgotada vai para erro, com last_error e alerta. Nunca fica preso.
     */
    @Test
    void falhaPersistenteReagendaDepoisErro() throws Exception {
        resposta = n -> sobrecarregada();
        UUID id = pedido(StatusPedido.PAGO, "", 0);
        enfileirarGerar(id);
        Set<StatusPedido> vistos = ConcurrentHashMap.newKeySet();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(20)).until(() -> {
            vistos.add(ler(id).getStatus());
            return ler(id).getStatus() == StatusPedido.ERRO;
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> emailsPara("alertas@clama.me") == 1);
        Thread.sleep(500);

        Pedido p = ler(id);
        assertThat(p.getRetryCount()).isEqualTo(3);
        assertThat(p.getLastError()).startsWith("Erro ao gerar oração: ").contains("Overloaded");
        assertThat(CHAMADAS.get()).isEqualTo(4 * 4 * 3);
        assertThat(vistos).contains(StatusPedido.GERANDO_ORACAO, StatusPedido.AGUARDANDO_REENVIO);
        assertThat(linhasNaFila()).isZero();
        assertThat(caixa.enviados()).hasSize(1);
        assertThat(caixa.enviados().get(0).texto()).contains("Número de Retentativas: 3");
    }

    /** Sem prompt ativo (ou mais de um) e falha de geracao como as outras: nunca fica preso em gerando_oracao. */
    @Test
    void semPromptAtivoSegueOCaminhoDeFalha() {
        jdbc.update("UPDATE prompts_prompttemplate SET ativo = false");
        UUID id = pedido(StatusPedido.PAGO, "", 3); // ultima rodada: esgotou = erro
        enfileirarGerar(id);
        await().atMost(Duration.ofSeconds(10)).until(() -> ler(id).getStatus() == StatusPedido.ERRO);
        assertThat(ler(id).getLastError()).isEqualTo("Nenhum template de prompt ativo encontrado");
        assertThat(CHAMADAS.get()).isZero();
        await().atMost(Duration.ofSeconds(5)).until(() -> emailsPara("alertas@clama.me") == 1);
    }

    /** Envio esgotado (30/60/120 s, aqui 100/200/400 ms) = erro e alerta; o cliente nao recebeu nada. */
    @Test
    void falhaFinalDoEnvioVaiParaErro() throws Exception {
        ENVIO_FALHA_PARA.add("maria@exemplo.com");
        UUID id = pedido(StatusPedido.ORACAO_GERADA, ORACAO, 0);
        tx.executeWithoutResult(s -> fila.enfileirar(enviar, JobEnviarOracao.instancia(id), id));
        await().atMost(Duration.ofSeconds(10)).until(() -> ler(id).getStatus() == StatusPedido.ERRO);
        await().atMost(Duration.ofSeconds(5)).until(() -> emailsPara("alertas@clama.me") == 1);
        Thread.sleep(300);

        assertThat(TENTATIVAS_DE_ENVIO_COM_FALHA.get()).isEqualTo(4);
        assertThat(emailsPara("maria@exemplo.com")).isZero();
        assertThat(caixa.enviados()).hasSize(1);
        assertThat(linhasNaFila()).isZero();
    }

    /**
     * Um alerta por pedido por hora (o cache alert_sent do signal), marcado so depois do commit: um rollback nao
     * suprime o proximo. Passada 1 h, alerta de novo.
     */
    @Test
    void alertaDeduplicadoPorUmaHora() throws Exception {
        UUID id = pedido(StatusPedido.ERRO, "", 0);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            alerta.registrar(id);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        tx.executeWithoutResult(s -> alerta.registrar(id));
        await().atMost(Duration.ofSeconds(5)).until(() -> caixa.enviados().size() == 1);

        // dentro da hora: nada, mesmo com retry_count novo (a chave da Resend seria outra)
        jdbc.update("UPDATE orders_pedido SET retry_count = 1 WHERE id = ?", id);
        tx.executeWithoutResult(s -> alerta.registrar(id));
        assertThat(linhasNaFila()).isZero();
        Thread.sleep(500);
        assertThat(caixa.enviados()).hasSize(1);

        alerta.relogio = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(61));
        tx.executeWithoutResult(s -> alerta.registrar(id));
        await().atMost(Duration.ofSeconds(5)).until(() -> caixa.enviados().size() == 2);
        assertThat(caixa.enviados().get(1).chaveDeIdempotencia()).isEqualTo("alerta/" + id + "/1");
        assertThat(caixa.enviados()).allSatisfy(e -> assertThat(e.para()).isEqualTo("alertas@clama.me"));
    }

    /** Payload so com o ID, e a instancia e "<tipo>/<id>". */
    @Test
    void payloadSoComOId() {
        UUID id = pedido(StatusPedido.PAGO, "", 0);
        tx.executeWithoutResult(s -> fila.enfileirar(gerar, JobGerarOracao.instancia(id), id,
                java.time.Instant.now().plusSeconds(3600)));
        Map<String, Object> linha = jdbc.queryForMap("select task_instance, convert_from(task_data, 'UTF8') as d"
                + " from scheduled_tasks");
        assertThat(linha).containsEntry("task_instance", "gerar-oracao/" + id).containsEntry("d", "\"" + id + "\"");
    }
}
