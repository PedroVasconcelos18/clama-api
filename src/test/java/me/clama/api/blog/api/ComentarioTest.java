package me.clama.api.blog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.blog.Comentario;
import me.clama.api.blog.ComentarioRepository;
import me.clama.api.blog.PostEspelho;
import me.clama.api.blog.PostEspelhoRepository;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** GET/PATCH/DELETE /api/blog/comments/{id}/ (o resto esta nos cenarios blog_comentario_id_* do contrato). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class ComentarioTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00Z");

    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PostEspelhoRepository espelhos;
    @Autowired ComentarioRepository comentarios;
    @Autowired RelogioDeTeste.Fixavel relogio;
    ApiDoBlog api;
    PostEspelho espelho;

    @BeforeEach
    void prepara() {
        api = new ApiDoBlog(porta, jdbc, usuarios, tokens);
        api.limpar();
        espelho = api.espelho(espelhos, "salmo-23", PostEspelho.PUBLICADO);
        relogio.fixar(AGORA);
    }

    @AfterEach
    void limpa() {
        relogio.fixar(null);
        api.limpar();
    }

    private static String rota(Comentario c) {
        return "/api/blog/comments/" + c.getId() + "/";
    }

    /** Exatamente 15 min ainda edita (e o updated_at vem do relogio); 15 min e 1 s ja nao. A validacao vem antes. */
    @Test
    void edicaoForaDaJanelaDe15Min400() throws Exception {
        Usuario ana = api.usuario("Ana Souza", false);
        Comentario noLimite = api.comentario(comentarios, ana, espelho, AGORA.minusSeconds(15 * 60));
        Comentario antigo = api.comentario(comentarios, ana, espelho, AGORA.minusSeconds(15 * 60 + 1));
        ApiDoBlog.Sessao s = api.sessao(ana);

        HttpResponse<String> r = api.enviar("PATCH", rota(antigo), "{\"conteudo\": \"Tarde demais\"}", s);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"code\":\"comentario_muito_antigo\",\"pastoral_message\":\"Esse comentário foi"
                + " escrito há mais de 15 minutos e não pode mais ser editado.\"}");
        assertThat(api.enviar("PATCH", rota(antigo), "{\"conteudo\": \"ab\"}", s).body()).contains("comentario_invalido");

        r = api.enviar("PATCH", rota(noLimite), "{\"conteudo\": \"  Editado: que merda  \"}", s);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(ApiDoBlog.JSON.readTree(r.body()).get("conteudo").asString()).isEqualTo("Editado: que merda");
        assertThat(ApiDoBlog.JSON.readTree(r.body()).get("updated_at").asString()).isEqualTo("2026-11-20T12:00:00-03:00");
        assertThat(jdbc.queryForObject("select is_suspeito from blog_comentario where id = ?", Boolean.class,
                noLimite.getId())).isTrue();

        // PATCH sem conteudo tambem salva (save() do Django: updated_at anda)
        jdbc.update("update blog_comentario set updated_at = created_at where id = ?", noLimite.getId());
        assertThat(api.enviar("PATCH", rota(noLimite), "{}", s).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select updated_at = '2026-11-20T15:00:00Z' from blog_comentario where id = ?",
                Boolean.class, noLimite.getId())).isTrue();
    }

    @Test
    void donoEditaAdminSoApaga() throws Exception {
        Usuario ana = api.usuario("Ana", false);
        Usuario admin = api.usuario("Admin", true);
        Comentario c = api.comentario(comentarios, ana, espelho, AGORA);
        ApiDoBlog.Sessao sa = api.sessao(admin);
        for (String metodo : new String[] {"GET", "PATCH"}) {
            HttpResponse<String> r = api.enviar(metodo, rota(c), metodo.equals("GET") ? null : "{}", sa);
            assertThat(r.statusCode()).as(metodo).isEqualTo(403);
            assertThat(r.body()).isEqualTo("{\"detail\":\"Sem permissão para editar.\"}");
        }
        HttpResponse<String> outro = api.enviar("DELETE", rota(c), null, api.sessao(api.usuario("Beto", false)));
        assertThat(outro.statusCode()).isEqualTo(403);
        assertThat(outro.body()).isEqualTo("{\"detail\":\"Sem permissão para apagar.\"}");
        assertThat(api.enviar("DELETE", rota(c), null, sa).statusCode()).isEqualTo(204);
        HttpResponse<String> sumiu = api.enviar("DELETE", rota(c), null, sa);
        assertThat(sumiu.statusCode()).isEqualTo(404);
        assertThat(sumiu.body()).isEqualTo("{\"detail\":\"No Comentario matches the given query.\"}");
        assertThat(api.enviar("GET", rota(c), null, null).statusCode()).isEqualTo(401);
        // uuid com maiuscula nao e rota (conversor uuid do Django): 404 de URL, nem 401
        assertThat(api.enviar("GET", rota(c).toUpperCase(java.util.Locale.ROOT).replace("/API/BLOG/COMMENTS/",
                "/api/blog/comments/"), null, null).statusCode()).isEqualTo(404);
    }
}
