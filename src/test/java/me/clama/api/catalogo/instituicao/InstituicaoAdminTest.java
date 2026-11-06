package me.clama.api.catalogo.instituicao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Multipart da logo como o MultiPartParser do Django: input de arquivo vazio e Content-Type com parametros. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class InstituicaoAdminTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String F = "TesteFronteira";

    @Value("${local.server.port}")
    int porta;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    ServicoDeTokens tokens;

    String cookies;
    String csrf;

    @BeforeEach
    void preparar() throws Exception {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("delete from instituicoes_instituicao");
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        Usuario u = new Usuario();
        u.setEmail("admin-inst@exemplo.com");
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

    /** partes: {nome, valor} para campo; {nome, filename, content-type, conteudo} para arquivo. */
    JsonNode multipart(String metodo, String caminho, int esperado, String[]... partes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String[] p : partes) {
            String cab = p.length == 2 ? "" : "; filename=\"" + p[1] + "\"\r\nContent-Type: " + p[2];
            out.writeBytes(("--" + F + "\r\nContent-Disposition: form-data; name=\"" + p[0] + "\"" + cab + "\r\n\r\n"
                    + p[p.length - 1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + F + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + porta + caminho)).header("Cookie", cookies).header("X-CSRFToken", csrf)
                .header("Content-Type", "multipart/form-data; boundary=" + F)
                .method(metodo, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(esperado);
        return JSON.readTree(r.body());
    }

    @Test
    void logoVaziaNoFormularioEIgnorada() throws Exception {
        // logo_file="" (allow_null) e input de arquivo vazio (filename="", vira campo "" no Django): sem logo
        assertThat(multipart("POST", "/api/admin/instituicoes/", 201, new String[] {"nome", "A"},
                new String[] {"logo_file", ""}).get("logo").asString()).isEmpty();
        JsonNode b = multipart("POST", "/api/admin/instituicoes/", 201, new String[] {"nome", "B"},
                new String[] {"logo_file", "", "application/octet-stream", ""});
        assertThat(b.get("logo").asString()).isEmpty();
        // PATCH com logo vazia nao apaga a logo que ja existe
        String id = b.get("id").asString();
        multipart("PATCH", "/api/admin/instituicoes/" + id + "/", 200,
                new String[] {"logo_file", "l.png", "image/png", "png"});
        JsonNode depois = multipart("PATCH", "/api/admin/instituicoes/" + id + "/", 200, new String[] {"nome", "B2"},
                new String[] {"logo_file", "", "application/octet-stream", ""});
        assertThat(depois.get("logo").asString()).isEqualTo("data:image/png;base64,cG5n");
    }

    @Test
    void contentTypeDaLogoSemParametrosEMinusculo() throws Exception {
        assertThat(multipart("POST", "/api/admin/instituicoes/", 201, new String[] {"nome", "A"},
                new String[] {"logo_file", "l.png", "image/png; charset=x", "png"}).get("logo").asString())
                .isEqualTo("data:image/png;base64,cG5n");
        assertThat(multipart("POST", "/api/admin/instituicoes/", 201, new String[] {"nome", "B"},
                new String[] {"logo_file", "l.png", " IMAGE/PNG ", "png"}).get("logo").asString())
                .isEqualTo("data:image/png;base64,cG5n");
    }
}
