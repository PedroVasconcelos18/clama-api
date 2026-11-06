package me.clama.api.documento;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AnthropicFilesService (clama/documents/services/anthropic_files.py). Sem chave valida (vazia ou a de
 * desenvolvimento) fica em modo mock: nenhuma chamada HTTP. Timeout de 60 s como o httpx.Client(timeout=60.0); aqui o
 * de requisicao vale ate chegar a resposta (inclui o envio do arquivo), no httpx e por operacao de leitura/escrita.
 */
@Component
public class ArquivosAnthropic {
    private static final Logger LOG = LoggerFactory.getLogger(ArquivosAnthropic.class);
    private static final Set<String> CHAVES_MOCK = Set.of("", "test_api_key_for_local_development");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom ALEATORIO = new SecureRandom();

    /** AnthropicFilesError: a mensagem e a que o Django poe no corpo do 500. */
    public static class Falha extends RuntimeException {
        public Falha(String mensagem) {
            super(mensagem);
        }
    }

    private final String chave;
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT).build();

    public ArquivosAnthropic(@Value("${ANTHROPIC_API_KEY:}") String chave,
            @Value("${clama.anthropic.files-url:https://api.anthropic.com/v1/files}") String url) {
        this.chave = chave == null ? "" : chave;
        this.url = url;
    }

    public boolean mock() {
        return CHAVES_MOCK.contains(chave);
    }

    /**
     * upload_file: multipart files={"file": (nome, conteudo, tipo_mime)} do httpx, com o arquivo lido do disco em
     * streaming. Modo mock: file-mock-<8 primeiros caracteres do id>, sem ler o arquivo.
     */
    public String enviar(UUID documentoId, String nomeArquivo, String tipoMime, Path arquivo) {
        if (mock()) {
            LOG.info("Upload mock realizado documento_id={}", documentoId);
            return "file-mock-" + documentoId.toString().substring(0, 8);
        }
        String fronteira = HexFormat.of().formatHex(aleatorio16());
        byte[] cabecalho = ("--" + fronteira + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + parametro(nomeArquivo) + "\"\r\nContent-Type: " + tipoMime + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] fim = ("\r\n--" + fronteira + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher corpo;
        try {
            corpo = BodyPublishers.concat(BodyPublishers.ofByteArray(cabecalho), BodyPublishers.ofFile(arquivo),
                    BodyPublishers.ofByteArray(fim));
        } catch (FileNotFoundException e) {
            throw new Falha("Arquivo local não encontrado: " + nomeArquivo);
        }
        HttpResponse<String> r = enviar(requisicao(url).header("Content-Type", "multipart/form-data; boundary=" + fronteira)
                .POST(corpo).build());
        if (r.statusCode() != 200) {
            LOG.error("Erro no upload para Anthropic documento_id={} status_code={} error={}", documentoId,
                    r.statusCode(), primeiros(r.body(), 500));
            throw new Falha("Erro " + r.statusCode() + " ao fazer upload: " + primeiros(r.body(), 200));
        }
        String fileId;
        try {
            JsonNode id = JSON.readTree(r.body()).path("id");
            fileId = id.isString() ? id.asString() : "";
        } catch (JacksonException e) {
            fileId = "";
        }
        if (fileId.isEmpty()) {
            throw new Falha("Resposta da API não contém file_id");
        }
        LOG.info("Upload para Anthropic realizado documento_id={} file_id={}", documentoId, fileId);
        return fileId;
    }

    /** delete_file: 404 conta como sucesso (ja nao existe). */
    public boolean apagar(String fileId) {
        if (mock()) {
            LOG.info("Delete mock realizado file_id={}", fileId);
            return true;
        }
        HttpResponse<String> r = enviar(requisicao(url + "/" + fileId).DELETE().build());
        if (r.statusCode() == 404) {
            LOG.warn("Arquivo não encontrado na Anthropic (já deletado?) file_id={}", fileId);
            return true;
        }
        if (r.statusCode() != 200) {
            LOG.error("Erro ao deletar arquivo na Anthropic file_id={} status_code={} error={}", fileId, r.statusCode(),
                    primeiros(r.body(), 500));
            throw new Falha("Erro " + r.statusCode() + " ao deletar: " + primeiros(r.body(), 200));
        }
        return true;
    }

    /** list_files: o "data" da resposta. */
    public List<Map<String, Object>> listar() {
        if (mock()) {
            return List.of();
        }
        HttpResponse<String> r = enviar(requisicao(url).GET().build());
        if (r.statusCode() != 200) {
            throw new Falha("Erro " + r.statusCode() + " ao listar: " + primeiros(r.body(), 200));
        }
        JsonNode data = JSON.readTree(r.body()).path("data");
        return data.isArray() ? JSON.convertValue(data, new TypeReference<>() {}) : List.of();
    }

    private HttpRequest.Builder requisicao(String destino) {
        return HttpRequest.newBuilder(URI.create(destino)).timeout(TIMEOUT)
                .header("x-api-key", chave)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "files-api-2025-04-14");
    }

    /** httpx.RequestError vira "Erro de conexão: ...". */
    private HttpResponse<String> enviar(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.error("Erro de conexão com Anthropic {} {}", req.method(), e.toString());
            throw new Falha("Erro de conexão: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Falha("Erro de conexão: interrompido");
        }
    }

    private static byte[] aleatorio16() {
        byte[] b = new byte[16];
        ALEATORIO.nextBytes(b);
        return b;
    }

    /** _format_form_param do httpx: aspas viram %22, barra invertida dobra, controles viram %XX. */
    private static String parametro(String v) {
        StringBuilder b = new StringBuilder();
        for (char c : v.toCharArray()) {
            if (c == '"') {
                b.append("%22");
            } else if (c == '\\') {
                b.append("\\\\");
            } else if (c < 0x20 && c != 0x1b) {
                b.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** error_detail[:n] do Python (code points). */
    private static String primeiros(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }
}
