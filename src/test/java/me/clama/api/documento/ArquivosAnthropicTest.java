package me.clama.api.documento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Porte de clama/documents/services/anthropic_files.py contra um stub HTTP (nunca a Anthropic de verdade). */
class ArquivosAnthropicTest {
    static final UUID ID = UUID.fromString("44444444-4444-4444-8444-000000000001");

    record Recebido(String metodo, String caminho, Map<String, List<String>> headers, byte[] corpo) {}

    HttpServer server;
    final List<Recebido> recebidos = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String resposta = "{\"id\":\"file_011CNha8iCJcU1wXNR6q4V8w\"}";

    @TempDir
    Path tmp;

    @BeforeEach
    void sobe() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/files", ex -> {
            recebidos.add(new Recebido(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders(),
                    ex.getRequestBody().readAllBytes()));
            byte[] b = resposta.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    ArquivosAnthropic api(String chave) {
        return new ArquivosAnthropic(chave, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/files");
    }

    ArquivosAnthropic api() {
        return api("sk-ant-teste");
    }

    @Test
    void uploadComHeadersEMultipartDoHttpx() throws Exception {
        Path arq = Files.write(tmp.resolve("x"), "%PDF-1.4 conteudo".getBytes(StandardCharsets.UTF_8));

        String fileId = api().enviar(ID, "ação_1.pdf", "application/pdf", arq);

        assertThat(fileId).isEqualTo("file_011CNha8iCJcU1wXNR6q4V8w");
        assertThat(recebidos).hasSize(1);
        Recebido r = recebidos.getFirst();
        assertThat(r.metodo()).isEqualTo("POST");
        assertThat(r.caminho()).isEqualTo("/v1/files");
        assertThat(r.headers().get("X-api-key")).containsExactly("sk-ant-teste");
        assertThat(r.headers().get("Anthropic-version")).containsExactly("2023-06-01");
        assertThat(r.headers().get("Anthropic-beta")).containsExactly("files-api-2025-04-14");
        String tipo = r.headers().get("Content-type").getFirst();
        assertThat(tipo).matches("multipart/form-data; boundary=[0-9a-f]{32}");
        String fronteira = tipo.substring(tipo.indexOf('=') + 1);
        // files={"file": (nome, conteudo, tipo_mime)} do httpx 0.28, byte a byte
        ByteArrayOutputStream esperado = new ByteArrayOutputStream();
        esperado.writeBytes(("--" + fronteira + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ação_1.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        esperado.writeBytes(Files.readAllBytes(arq));
        esperado.writeBytes(("\r\n--" + fronteira + "--\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(r.corpo()).isEqualTo(esperado.toByteArray());
        assertThat(r.headers().get("Content-length")).containsExactly(String.valueOf(esperado.size()));
    }

    @Test
    void uploadNao200ViraFalhaComOs200PrimeirosCaracteres() throws Exception {
        Path arq = Files.write(tmp.resolve("x"), new byte[] {1});
        status = 400;
        resposta = "é".repeat(300);
        assertThatThrownBy(() -> api().enviar(ID, "x.txt", "text/plain", arq))
                .isInstanceOf(ArquivosAnthropic.Falha.class)
                .hasMessage("Erro 400 ao fazer upload: " + "é".repeat(200));
    }

    @Test
    void uploadSemIdNaRespostaViraFalha() throws Exception {
        Path arq = Files.write(tmp.resolve("x"), new byte[] {1});
        resposta = "{\"id\":\"\"}";
        assertThatThrownBy(() -> api().enviar(ID, "x.txt", "text/plain", arq))
                .isInstanceOf(ArquivosAnthropic.Falha.class).hasMessage("Resposta da API não contém file_id");
    }

    @Test
    void arquivoLocalSumidoViraFalhaSemChamarAApi() {
        assertThatThrownBy(() -> api().enviar(ID, "x.txt", "text/plain", tmp.resolve("nao-existe.txt")))
                .isInstanceOf(ArquivosAnthropic.Falha.class).hasMessageContaining("Arquivo local não encontrado");
        assertThat(recebidos).isEmpty();
    }

    @Test
    void apagar200e404SaoSucesso() {
        assertThat(api().apagar("file_abc")).isTrue();
        status = 404;
        assertThat(api().apagar("file_abc")).isTrue(); // "Arquivo já não existe, considera sucesso"
        assertThat(recebidos).extracting(Recebido::metodo, Recebido::caminho)
                .containsOnly(org.assertj.core.groups.Tuple.tuple("DELETE", "/v1/files/file_abc"));
        assertThat(recebidos.getFirst().headers().get("Anthropic-beta")).containsExactly("files-api-2025-04-14");
    }

    @Test
    void apagar5xxViraFalha() {
        status = 503;
        resposta = "{\"type\":\"error\"}";
        assertThatThrownBy(() -> api().apagar("file_abc")).isInstanceOf(ArquivosAnthropic.Falha.class)
                .hasMessage("Erro 503 ao deletar: {\"type\":\"error\"}");
    }

    @Test
    void listarDevolveData() {
        resposta = "{\"data\":[{\"id\":\"file_1\",\"filename\":\"a.pdf\"}],\"has_more\":false}";
        assertThat(api().listar()).containsExactly(Map.of("id", "file_1", "filename", "a.pdf"));
        status = 500;
        resposta = "erro";
        assertThatThrownBy(() -> api().listar()).hasMessage("Erro 500 ao listar: erro");
    }

    @Test
    void conexaoRecusadaViraFalhaDeConexao() throws Exception {
        Path arq = Files.write(tmp.resolve("x"), new byte[] {1});
        ArquivosAnthropic api = api();
        server.stop(0);
        assertThatThrownBy(() -> api.enviar(ID, "x.txt", "text/plain", arq)).isInstanceOf(ArquivosAnthropic.Falha.class)
                .hasMessageStartingWith("Erro de conexão: ");
        assertThatThrownBy(() -> api.apagar("file_abc")).hasMessageStartingWith("Erro de conexão: ");
    }

    @Test
    void modoMockNaoFazChamada() {
        for (String chave : new String[] {"", null, "test_api_key_for_local_development"}) {
            ArquivosAnthropic api = api(chave);
            assertThat(api.enviar(ID, "x.pdf", "application/pdf", tmp.resolve("nem-existe"))).isEqualTo("file-mock-44444444");
            assertThat(api.apagar("file_abc")).isTrue();
            assertThat(api.listar()).isEmpty();
        }
        assertThat(recebidos).isEmpty();
    }
}
