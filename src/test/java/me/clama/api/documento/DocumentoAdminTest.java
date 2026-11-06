package me.clama.api.documento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O que os cenarios gravados (modo mock) nao cobrem: Files API "de verdade" (stub), disco, nomes hostis e limites.
 * Limites pequenos aqui para nao alocar 100 MB: o do serializer (4 KB) e o do multipart do Spring (8 KB).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ANTHROPIC_API_KEY=sk-ant-teste",
        "clama.media-root=target/media-test",
        "clama.documentos.tamanho-maximo=4KB",
        "spring.servlet.multipart.max-file-size=8KB",
        "spring.servlet.multipart.max-request-size=9KB"})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class DocumentoAdminTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "/api/admin/documentos/";
    static final String FRONTEIRA = "TesteFronteira";
    static final String NAO_ARQUIVO = "O dado submetido não era um arquivo. Cheque o tipo de codificação no formulário.";

    static final HttpServer ANTHROPIC;
    static final List<String> RECEBIDOS = new CopyOnWriteArrayList<>();
    static volatile int status = 200;

    static {
        try {
            ANTHROPIC = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        ANTHROPIC.createContext("/v1/files", ex -> {
            RECEBIDOS.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + "\n"
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = (status == 200 ? "{\"id\":\"file_stub\"}" : "boom").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        ANTHROPIC.start();
    }

    @DynamicPropertySource
    static void anthropic(DynamicPropertyRegistry r) {
        r.add("clama.anthropic.files-url", () -> "http://127.0.0.1:" + ANTHROPIC.getAddress().getPort() + "/v1/files");
    }

    @AfterAll
    static void desce() {
        ANTHROPIC.stop(0);
    }

    @Value("${local.server.port}")
    int porta;

    @Value("${clama.media-root}")
    Path media;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    ServicoDeTokens tokens;

    @Autowired
    ArmazenamentoLocal armazenamento;

    @Autowired
    DocumentoRepository documentos;

    String cookies;
    String csrf;

    record Parte(String nome, String arquivo, String tipo, byte[] conteudo) {
        static Parte campo(String nome, String valor) {
            return new Parte(nome, null, null, valor.getBytes(StandardCharsets.UTF_8));
        }

        static Parte arquivo(String arquivo, byte[] conteudo) {
            return new Parte("arquivo", arquivo, "application/octet-stream", conteudo);
        }
    }

    @BeforeEach
    void preparar() throws Exception {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("delete from documents_documentocontexto");
        jdbc.update("delete from instituicoes_instituicao");
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        RECEBIDOS.clear();
        status = 200;
        Usuario u = new Usuario();
        u.setEmail("admin-docs@exemplo.com");
        u.setPassword("x");
        u.setNomeCompleto("Admin");
        u.setClamaAdmin(true);
        u.setActive(true);
        usuarios.saveAndFlush(u);
        String access = "clama_access=" + tokens.emitirPar(u).access();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/csrf/"))
                        .header("Cookie", access).build(),
                HttpResponse.BodyHandlers.ofString());
        csrf = JSON.readTree(r.body()).get("csrf_token").asString();
        cookies = access + "; " + r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("csrftoken="))
                .findFirst().orElseThrow().split(";")[0];
    }

    HttpResponse<String> enviar(String metodo, String caminho, String tipo, byte[] corpo, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Cookie", cookies).header("X-CSRFToken", csrf)
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(corpo));
        if (tipo != null) {
            b.header("Content-Type", tipo);
        }
        if (headers.length > 0) {
            b.headers(headers);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> multipart(String metodo, String caminho, Parte... partes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Parte p : partes) {
            out.writeBytes(("--" + FRONTEIRA + "\r\nContent-Disposition: form-data; name=\"" + p.nome() + "\""
                    + (p.arquivo() == null ? "" : "; filename=\"" + p.arquivo() + "\"\r\nContent-Type: " + p.tipo())
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(p.conteudo());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + FRONTEIRA + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return enviar(metodo, caminho, "multipart/form-data; boundary=" + FRONTEIRA, out.toByteArray());
    }

    UUID inserir(String arquivo, String fileId, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into documents_documentocontexto (id, created_at, updated_at, nome, descricao, arquivo,"
                + " tipo_mime, tamanho_bytes, anthropic_file_id, data_sincronizacao, ativo) values (?, now(), now(),"
                + " 'Doc', '', ?, 'application/pdf', 10, ?, null, ?)", id, arquivo.replace("<id>", id.toString()),
                fileId, ativo);
        return id;
    }

    static JsonNode json(HttpResponse<String> r) {
        return JSON.readTree(r.body());
    }

    static byte[] bytes(int n) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) 'a');
        return b;
    }

    @Test
    void ativarSemArquivoLocalFalhaSemAtivar() throws Exception {
        UUID id = inserir("documents/<id>/sumiu.pdf", null, false);

        HttpResponse<String> r = enviar("POST", BASE + id + "/toggle_ativo/", null, null);

        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(json(r).get("status").asString()).isEqualTo("error");
        assertThat(json(r).get("message").asString())
                .isEqualTo("Falha ao sincronizar com Anthropic: Arquivo local não encontrado: sumiu.pdf");
        assertThat(jdbc.queryForMap("select ativo, anthropic_file_id from documents_documentocontexto where id = ?", id))
                .containsEntry("ativo", false).containsEntry("anthropic_file_id", null);
        assertThat(RECEBIDOS).isEmpty();
    }

    @Test
    void uploadGravaNoDiscoEAtivarEnviaOArquivoGravado() throws Exception {
        HttpResponse<String> criado = multipart("POST", BASE, Parte.campo("nome", "Novena"),
                Parte.arquivo("Novena.pdf", "%PDF conteudo".getBytes(StandardCharsets.UTF_8)));
        assertThat(criado.statusCode()).isEqualTo(201);
        String id = json(criado).get("id").asString();
        assertThat(json(criado).get("arquivo").asString())
                .isEqualTo("http://localhost:" + porta + "/media/documents/" + id + "/Novena.pdf");
        assertThat(media.resolve("documents/" + id + "/Novena.pdf")).hasContent("%PDF conteudo");

        HttpResponse<String> r = enviar("POST", BASE + id + "/toggle_ativo/", null, null);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r)).isEqualTo(JSON.readTree(
                "{\"status\":\"ok\",\"message\":\"Documento 'Novena' ativado.\",\"ativo\":true,\"file_id\":\"file_stub\"}"));
        assertThat(RECEBIDOS).singleElement().asString().startsWith("POST /v1/files\n")
                .contains("filename=\"Novena.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF conteudo\r\n");
        assertThat(documentos.ativosSincronizados()).extracting(d -> d.getId().toString()).containsExactly(id);
        assertThat(documentos.findById(UUID.fromString(id)).orElseThrow().getDataSincronizacao()).isNotNull();
    }

    @Test
    void nomeDeArquivoNaoEscapaDaPasta() throws Exception {
        for (String hostil : new String[] {"../../../../etc/pass wd.txt", "..\\..\\..\\evil.txt", "/abs/x.pdf"}) {
            HttpResponse<String> r = multipart("POST", BASE, Parte.campo("nome", "Hostil"),
                    Parte.arquivo(hostil, bytes(3)));
            assertThat(r.statusCode()).as(hostil).isEqualTo(201);
            String id = json(r).get("id").asString();
            String nome = json(r).get("arquivo").asString().replaceAll(".*/media/documents/" + id + "/", "");
            assertThat(nome).as(hostil).isIn("pass_wd.txt", "evil.txt", "x.pdf");
            assertThat(media.resolve("documents").resolve(id).resolve(nome)).exists();
        }
        try (var arvore = Files.walk(media)) {
            assertThat(arvore.filter(Files::isRegularFile).map(p -> media.relativize(p).toString()))
                    .allMatch(p -> p.startsWith("documents/"));
        }
        assertThatThrownBy(() -> armazenamento.caminho("../fora.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> armazenamento.caminho("documents/../../fora.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> armazenamento.caminho("/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nomeComoODjangoGetValidNameColisaoETruncamento() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(armazenamento.salvar(id, "ação (1)!.pdf", new ByteArrayInputStream(bytes(1))))
                .isEqualTo("documents/" + id + "/ação_1.pdf");
        assertThat(armazenamento.salvar(id, "ação (1)!.pdf", new ByteArrayInputStream(bytes(1))))
                .matches("documents/" + id + "/ação_1_[A-Za-z0-9]{7}\\.pdf");
        String longo = armazenamento.salvar(id, "a".repeat(96) + ".txt", new ByteArrayInputStream(bytes(1)));
        assertThat(longo).hasSize(100).matches("documents/" + id + "/a{41}_[A-Za-z0-9]{7}\\.txt");

        UUID doc = inserir("documents/<id>/catecismo.pdf", null, false);
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> r = multipart("PATCH", BASE + doc + "/", Parte.arquivo("outro.txt", bytes(2)));
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json(r).get("arquivo").asString()).matches(i == 0 ? ".*/" + doc + "/outro\\.txt"
                    : ".*/" + doc + "/outro_[A-Za-z0-9]{7}\\.txt");
            assertThat(json(r).get("tipo_mime").asString()).isEqualTo("application/pdf"); // como o Django gravado
        }
    }

    @Test
    void uploadAcimaDoLimiteRecusado() throws Exception {
        String excede = "{\"arquivo\":[\"Arquivo excede limite de 0MB. Tamanho: 0.0MB\"]}";
        // acima do limite do serializer (4 KB), abaixo do multipart: o validate_arquivo
        HttpResponse<String> r = multipart("POST", BASE, Parte.campo("nome", "Grande"), Parte.arquivo("g.pdf", bytes(5000)));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r)).isEqualTo(JSON.readTree(excede));
        // acima do limite do multipart (8 KB): MaxUploadSizeExceededException vira o mesmo erro (sem validar o resto:
        // o nome ausente nao aparece)
        r = multipart("POST", BASE, Parte.arquivo("g.pdf", bytes(20_000)));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r)).isEqualTo(JSON.readTree(excede));
        assertThat(jdbc.queryForObject("select count(*) from documents_documentocontexto", Integer.class)).isZero();
        // instituicao: o mesmo excesso vira o erro da logo (o Django so recusa no validate_logo_file)
        r = multipart("POST", "/api/admin/instituicoes/", Parte.campo("nome", "Inst"),
                new Parte("logo_file", "l.png", "image/png", bytes(20_000)));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r)).isEqualTo(JSON.readTree(
                "{\"logo_file\":[\"A imagem excede o limite de 250 KB. Envie uma versão menor.\"]}"));
    }

    @Test
    void validacoesDoSerializerNaOrdemDoDrf() throws Exception {
        record Caso(Parte[] partes, String esperado) {}
        List<Caso> casos = List.of(
                // filename="" o Django trata como campo de texto: nao e arquivo
                new Caso(new Parte[] {Parte.campo("nome", "a"), Parte.arquivo("", new byte[0])},
                        "{\"arquivo\":[\"" + NAO_ARQUIVO + "\"]}"),
                // nome de arquivo que o sanitize_file_name descarta: parte ignorada
                new Caso(new Parte[] {Parte.campo("nome", "a"), Parte.arquivo("..", bytes(1))},
                        "{\"arquivo\":[\"Nenhum arquivo foi submetido.\"]}"),
                new Caso(new Parte[] {Parte.campo("nome", "a"), Parte.campo("arquivo", "")},
                        "{\"arquivo\":[\"" + NAO_ARQUIVO + "\"]}"),
                new Caso(new Parte[] {Parte.arquivo("foto.png", bytes(1))},
                        "{\"nome\":[\"Este campo é obrigatório.\"],\"arquivo\":[\"A extensão de arquivo “png” não é"
                                + " permitida. As extensões válidas são: pdf, txt, docx .\"]}"),
                new Caso(new Parte[] {Parte.campo("nome", "n".repeat(201)), Parte.arquivo("x.pdf", new byte[0])},
                        "{\"nome\":[\"Certifique-se de que este campo não tenha mais de 200 caracteres.\"],"
                                + "\"arquivo\":[\"O arquivo submetido está vázio.\"]}"),
                new Caso(new Parte[] {Parte.campo("nome", "a"), Parte.arquivo("a".repeat(97) + ".txt", bytes(1))},
                        "{\"arquivo\":[\"Certifique-se de que o nome do arquivo tenho no máximo 100 caracteres"
                                + " (tem 101).\"]}"),
                new Caso(new Parte[] {Parte.campo("nome", "a"), Parte.arquivo("semext", bytes(1))},
                        "{\"arquivo\":[\"A extensão de arquivo “” não é permitida. As extensões válidas são:"
                                + " pdf, txt, docx .\"]}"));
        for (Caso c : casos) {
            HttpResponse<String> r = multipart("POST", BASE, c.partes());
            assertThat(r.statusCode()).as(c.esperado()).isEqualTo(400);
            assertThat(json(r)).as(c.esperado()).isEqualTo(JSON.readTree(c.esperado()));
        }
        // extensao em maiuscula e docx: tipo pela extensao (mimetypes), nao pelo content-type enviado
        HttpResponse<String> r = multipart("POST", BASE, Parte.campo("nome", " a "), Parte.campo("ativo", "true"),
                Parte.arquivo("X.DOCX", bytes(1)));
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(json(r).get("nome").asString()).isEqualTo("a");
        assertThat(json(r).get("descricao").asString()).isEmpty();
        assertThat(json(r).get("ativo").asBoolean()).isTrue(); // ativo pelo form nao sincroniza, como no Django
        assertThat(json(r).get("tipo_mime").asString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(RECEBIDOS).isEmpty();
    }

    @Test
    void soMultipartOuFormulario() throws Exception {
        HttpResponse<String> r = enviar("POST", BASE, "application/json", "{\"nome\":\"a\"}".getBytes());
        assertThat(r.statusCode()).isEqualTo(415);
        assertThat(json(r)).isEqualTo(JSON.readTree("{\"detail\":\"Tipo de mídia \\\"application/json\\\" não é suportado.\"}"));
        r = enviar("POST", BASE, null, null);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r)).isEqualTo(JSON.readTree(
                "{\"nome\":[\"Este campo é obrigatório.\"],\"arquivo\":[\"Nenhum arquivo foi submetido.\"]}"));
        // 404 vem antes do corpo no PATCH
        r = enviar("PATCH", BASE + UUID.randomUUID() + "/", "application/json", "{}".getBytes());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r)).isEqualTo(JSON.readTree("{\"detail\":\"No DocumentoContexto matches the given query.\"}"));
    }

    @Test
    void deleteComFalhaNaAnthropicNaoApaga() throws Exception {
        UUID id = inserir("documents/<id>/x.pdf", "file_abc", true);
        status = 500;
        HttpResponse<String> r = enviar("DELETE", BASE + id + "/", null, null);
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(json(r)).isEqualTo(JSON.readTree("{\"status\":\"error\",\"message\":\"Falha ao remover da Anthropic:"
                + " Erro 500 ao deletar: boom. Documento não foi removido.\"}"));
        assertThat(documentos.existsById(id)).isTrue();

        status = 404; // ja nao existe na Anthropic: segue com a remocao
        r = enviar("DELETE", BASE + id + "/", null, null);
        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(documentos.existsById(id)).isFalse();
        assertThat(RECEBIDOS).allMatch(s -> s.startsWith("DELETE /v1/files/file_abc"));
    }

    @Test
    void urlAbsolutaComoOBuildAbsoluteUriDoDjango() throws Exception {
        UUID id = inserir("documents/<id>/ação (1)~.pdf", null, false);
        HttpResponse<String> r = enviar("GET", BASE + id + "/", null, null, "X-Forwarded-Proto", "https");
        assertThat(json(r).get("arquivo").asString())
                .isEqualTo("https://localhost:" + porta + "/media/documents/" + id + "/a%C3%A7%C3%A3o%20(1)~.pdf");
        assertThat(json(r).get("tamanho_formatado").asString()).isEqualTo("10 B");
        // f"{x:.1f}" do Python: meio exato arredonda para o par
        assertThat(List.of(1023L, 1280L, 1331L, 2560L, 1_310_720L, 104_857_601L)).map(DocumentoDtos::tamanhoFormatado)
                .containsExactly("1023 B", "1.2 KB", "1.3 KB", "2.5 KB", "1.2 MB", "100.0 MB");
    }
}
