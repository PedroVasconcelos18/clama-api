package me.clama.api.blog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.blog.Comentario;
import me.clama.api.blog.ComentarioRepository;
import me.clama.api.blog.JanelaFixa;
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

/** Cookie de auth valido e sem X-CSRFToken: 403 csrf_invalido em cada familia de escrita do blog, sem efeito. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class CsrfDoBlogTest {
    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PostEspelhoRepository espelhos;
    @Autowired ComentarioRepository comentarios;
    @Autowired JanelaFixa janela;
    ApiDoBlog api;

    @BeforeEach
    void prepara() {
        janela.limpar();
        api = new ApiDoBlog(porta, jdbc, usuarios, tokens);
        api.limpar();
    }

    @AfterEach
    void limpa() {
        api.limpar();
    }

    private void exige403(HttpResponse<String> r, String quem) {
        assertThat(r.statusCode()).as(quem + ": " + r.body()).isEqualTo(403);
        assertThat(r.body()).as(quem).contains("\"code\":\"csrf_invalido\"");
    }

    @Test
    void escritasSemCsrfDao403() throws Exception {
        Usuario admin = api.usuario("Admin", true);
        Usuario ana = api.usuario("Ana", false);
        PostEspelho espelho = api.espelho(espelhos, "salmo-23", PostEspelho.PUBLICADO);
        Comentario c = api.comentario(comentarios, ana, espelho, java.time.Instant.now());
        ApiDoBlog.Sessao adm = ApiDoBlog.semCsrf(api.sessao(admin));
        ApiDoBlog.Sessao dona = ApiDoBlog.semCsrf(api.sessao(ana));
        String ban = "{\"customer_id\": " + ana.getId() + ", \"motivo\": \"Spam nos comentários\"}";

        // admin do blog
        exige403(api.enviar("POST", "/api/blog/admin/banned-customers/", ban, adm), "POST ban");
        exige403(api.enviar("DELETE", "/api/blog/admin/banned-customers/" + ana.getId() + "/", null, adm), "DELETE ban");
        exige403(api.enviar("DELETE", "/api/blog/admin/comments/" + c.getId() + "/", null, adm), "DELETE comentario admin");
        // curtida
        exige403(api.enviar("POST", "/api/blog/posts/salmo-23/like/", null, dona), "POST like");
        // comentario do dono
        exige403(api.enviar("PATCH", "/api/blog/comments/" + c.getId() + "/", "{\"conteudo\": \"novo\"}", dona),
                "PATCH comentario");
        exige403(api.enviar("DELETE", "/api/blog/comments/" + c.getId() + "/", null, dona), "DELETE comentario");

        assertThat(jdbc.queryForObject("select count(*) from blog_customerbanido", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from blog_reacao", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from blog_comentario", Long.class)).isOne();
    }
}
