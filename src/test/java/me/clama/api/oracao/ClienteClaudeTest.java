package me.clama.api.oracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import me.clama.api.contrato.StubDeIntegracoes;
import me.clama.api.documento.DocumentoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * gerar_oracao do AnthropicClient (clama/prayer_generation/services/anthropic_client.py) contra um stub HTTP: payload,
 * headers, retry do @with_retry (3 tentativas, 1 s e 2 s, so em conexao/timeout/529) e a classificacao dos erros.
 */
class ClienteClaudeTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String OK = """
            {"status": 200, "corpo": {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
              "content": [{"type": "text", "text": "Senhor, acolhe a Maria.\\n\\nAmém."}], "stop_reason": "end_turn",
              "stop_sequence": null, "usage": {"input_tokens": 812, "output_tokens": 164,
              "cache_creation_input_tokens": 0, "cache_read_input_tokens": 790}}}""";
    static final String SOBRECARGA = """
            {"status": 529, "corpo": {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}}""";
    static final String SEM_CREDITOS = """
            {"status": 400, "corpo": {"type": "error", "error": {"type": "invalid_request_error", "message":
              "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."}}}""";

    final StubDeIntegracoes stub = new StubDeIntegracoes("requisicoes a Anthropic",
            Map.of("chave", "x-api-key", "versao", "anthropic-version", "beta", "anthropic-beta", "bearer",
                    "Authorization"));
    final List<Duration> esperas = new ArrayList<>();

    @AfterEach
    void parar() {
        stub.parar();
    }

    ClienteClaude cliente(String url, Duration timeout) {
        return new ClienteClaude("sk-ant-teste", url, false, null, null, timeout, esperas::add);
    }

    String gerar(String... fileIds) {
        return cliente(stub.url(), Duration.ofSeconds(30)).gerar("Você é o Clama.", "Pedido: paz", List.of(fileIds),
                "simples");
    }

    void respostas(String... corpos) {
        stub.preparar(JSON.readTree("[" + String.join(",", corpos) + "]"));
    }

    static String requisicao(String beta, String conteudo) {
        return """
                {"metodo": "POST", "caminho": "/v1/messages", "chave": "sk-ant-teste", "versao": "2023-06-01",
                 "bearer": null, "beta": %s, "corpo": {"model": "claude-sonnet-4-6", "max_tokens": 1500,
                  "system": [{"type": "text", "text": "Você é o Clama.", "cache_control": {"type": "ephemeral"}}],
                  "messages": [{"role": "user", "content": %s}]}}""".formatted(beta, conteudo);
    }

    static final String SEM_DOCS = requisicao("null", "\"Pedido: paz\"");

    void conferir(String... requisicoes) {
        stub.conferir(JSON.readTree("[" + String.join(",", requisicoes) + "]"), "ClienteClaudeTest");
    }

    @Test
    void semDocumentosConteudoTextoESemBeta() {
        respostas(OK);
        assertThat(gerar()).isEqualTo("Senhor, acolhe a Maria.\n\nAmém.");
        conferir(SEM_DOCS);
        assertThat(esperas).isEmpty();
    }

    @Test
    void documentosPorFileIdComCacheEHeaderBeta() {
        respostas(OK);
        assertThat(gerar("file-b", "file-a")).isEqualTo("Senhor, acolhe a Maria.\n\nAmém.");
        conferir(requisicao("\"files-api-2025-04-14\"", """
                [{"type": "document", "source": {"type": "file", "file_id": "file-b"}, "cache_control": {"type": "ephemeral"}},
                 {"type": "document", "source": {"type": "file", "file_id": "file-a"}, "cache_control": {"type": "ephemeral"}},
                 {"type": "text", "text": "Pedido: paz"}]"""));
    }

    @Test
    void sobrecargaRetentaCom1e2Segundos() {
        respostas(SOBRECARGA, SOBRECARGA, OK);
        assertThat(gerar()).isEqualTo("Senhor, acolhe a Maria.\n\nAmém.");
        conferir(SEM_DOCS, SEM_DOCS, SEM_DOCS);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void sobrecargaEsgotadaViraErroDeGeracao() {
        respostas(SOBRECARGA, SOBRECARGA, SOBRECARGA);
        assertThatThrownBy(this::gerar).isExactlyInstanceOf(ErroDeGeracao.class).hasMessageContaining("529");
        conferir(SEM_DOCS, SEM_DOCS, SEM_DOCS);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void semCreditosNaoRetenta() {
        respostas(SEM_CREDITOS);
        assertThatThrownBy(this::gerar).isInstanceOf(CreditosEsgotados.class)
                .hasMessageStartingWith("Anthropic sem créditos: ");
        conferir(SEM_DOCS);
        assertThat(esperas).isEmpty();
    }

    /** 400 sem "credit balance", 429 e 500: ErroDeGeracao na hora (o SDK nao retenta: maxRetries 0). */
    @Test
    void outrosStatusViramErroDeGeracaoSemRetry() {
        for (int status : new int[] {400, 429, 500}) {
            respostas("""
                    {"status": %d, "corpo": {"type": "error", "error": {"type": "x", "message": "falhou"}}}"""
                    .formatted(status));
            assertThatThrownBy(this::gerar).as("%d", status).isExactlyInstanceOf(ErroDeGeracao.class)
                    .hasMessageStartingWith("Erro ao gerar oração: ");
            conferir(SEM_DOCS);
        }
        assertThat(esperas).isEmpty();
    }

    @Test
    void conexaoRecusadaRetenta3Vezes() {
        StubDeIntegracoes parado = new StubDeIntegracoes();
        String fechado = parado.url();
        parado.parar();
        assertThatThrownBy(() -> cliente(fechado, Duration.ofSeconds(30)).gerar("s", "m", List.of(), "simples"))
                .isExactlyInstanceOf(ErroDeGeracao.class);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void timeoutPorTentativaRetenta3Vezes() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        HttpServer lento = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        lento.createContext("/", t -> {
            chamadas.incrementAndGet();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            t.close();
        });
        lento.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        lento.start();
        try {
            ClienteClaude c = cliente("http://127.0.0.1:" + lento.getAddress().getPort(), Duration.ofMillis(200));
            assertThatThrownBy(() -> c.gerar("s", "m", List.of(), "simples")).isExactlyInstanceOf(ErroDeGeracao.class);
        } finally {
            lento.stop(0);
        }
        assertThat(chamadas).hasValue(3);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    /** content[0].text sem validacao: bloco nao-texto vira "Erro inesperado" (AttributeError no Django). */
    @Test
    void primeiroBlocoNaoTextoViraErroInesperado() {
        respostas("""
                {"status": 200, "corpo": {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
                  "content": [{"type": "tool_use", "id": "t1", "name": "x", "input": {}}], "stop_reason": "tool_use",
                  "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 1}}}""");
        assertThatThrownBy(this::gerar).isExactlyInstanceOf(ErroDeGeracao.class)
                .hasMessageStartingWith("Erro inesperado ao gerar oração: ");
    }

    /** Modo mock so no perfil test; nenhuma chamada HTTP. */
    @Test
    void mockSoNoPerfilTest() {
        respostas();
        for (String chave : new String[] {"", "test_api_key_for_local_development"}) {
            ClienteClaude mock = new ClienteClaude(chave, stub.url(), true, null, null, Duration.ofSeconds(30),
                    esperas::add);
            assertThat(mock.gerar(MontagemDoPromptTest.pedido("simples", "Maria Silva", null, null, "x")))
                    .startsWith("Senhor, venho diante de Ti em nome de Maria.\n\nTu conheces")
                    .contains("Pai, derrama Tua paz sobre Maria.")
                    .endsWith("---\n[MODO DESENVOLVIMENTO: Esta é uma oração de exemplo. Configure ANTHROPIC_API_KEY com "
                            + "créditos para gerar orações personalizadas via IA.]");
            assertThat(mock.gerar(MontagemDoPromptTest.pedido("simples", "", null, null, "x")))
                    .startsWith("Senhor, venho diante de Ti em nome de irmã.");

            ClienteClaude producao = new ClienteClaude(chave, stub.url(), false, null, null, Duration.ofSeconds(30),
                    esperas::add);
            assertThatThrownBy(() -> producao.gerar(MontagemDoPromptTest.pedido("simples", "Maria", null, null, "x")))
                    .isExactlyInstanceOf(ErroDeGeracao.class).hasMessageContaining("ANTHROPIC_API_KEY");
        }
        conferir();
    }

    String previa(ClienteClaude c) {
        return c.gerarPrevia("Você é o Clama.", "Pedido: paz");
    }

    /** _generate_raw: mesmo payload, mas nunca consulta documentos (o repositorio falha se tocado) nem poe o beta. */
    @Test
    void previaSemHeaderBetaESemDocumentos() {
        respostas(OK);
        DocumentoRepository docs = org.mockito.Mockito.mock(DocumentoRepository.class);
        org.mockito.Mockito.when(docs.ativosSincronizados()).thenThrow(new AssertionError("previa nao usa documentos"));
        ClienteClaude c = new ClienteClaude("sk-ant-teste", stub.url(), false, null, docs, Duration.ofSeconds(30),
                esperas::add);
        assertThat(previa(c)).isEqualTo("Senhor, acolhe a Maria.\n\nAmém.");
        conferir(SEM_DOCS);
        org.mockito.Mockito.verifyNoInteractions(docs);
    }

    @Test
    void previaNaoRetenta() {
        respostas(SOBRECARGA, OK);
        assertThatThrownBy(() -> previa(cliente(stub.url(), Duration.ofSeconds(30))))
                .isExactlyInstanceOf(ErroDeGeracao.class).hasMessageStartingWith("Erro ao gerar preview: ")
                .hasMessageContaining("529");
        conferir(SEM_DOCS);
        assertThat(esperas).isEmpty();
    }

    @Test
    void previaSemCreditosELevantaCreditosEsgotados() {
        respostas(SEM_CREDITOS);
        assertThatThrownBy(() -> previa(cliente(stub.url(), Duration.ofSeconds(30))))
                .isInstanceOf(CreditosEsgotados.class);
        conferir(SEM_DOCS);
    }

    @Test
    void previaSemChaveForaDeTest500() {
        respostas();
        for (String chave : new String[] {"", "test_api_key_for_local_development"}) {
            ClienteClaude producao = new ClienteClaude(chave, stub.url(), false, null, null, Duration.ofSeconds(30),
                    esperas::add);
            assertThatThrownBy(() -> previa(producao)).isExactlyInstanceOf(ErroDeGeracao.class)
                    .hasMessage("ANTHROPIC_API_KEY ausente");
            ClienteClaude mock = new ClienteClaude(chave, stub.url(), true, null, null, Duration.ofSeconds(30),
                    esperas::add);
            assertThat(previa(mock)).startsWith("Senhor, venho diante de Ti em nome de irmã.");
            assertThat(mock.gerarPrevia("s", "m", "Joana Lima")).contains("em nome de Joana.");
        }
        conferir();
    }
}
