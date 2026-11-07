package me.clama.api.blog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
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
import tools.jackson.databind.JsonNode;

/** /api/blog/admin/comments/ (o resto esta nos cenarios blog_admin_comentarios_* do contrato). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class ComentariosAdminTest {
    static final String PADRAO = "{\"detail\":\"Você não tem permissão para executar essa ação.\"}";

    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PostEspelhoRepository espelhos;
    @Autowired ComentarioRepository comentarios;
    @Autowired JanelaFixa janela;
    ApiDoBlog api;
    PostEspelho espelho;

    @BeforeEach
    void prepara() {
        janela.limpar();
        api = new ApiDoBlog(porta, jdbc, usuarios, tokens);
        api.limpar();
        espelho = api.espelho(espelhos, "salmo-23", PostEspelho.PUBLICADO);
    }

    @AfterEach
    void limpa() {
        api.limpar();
    }

    /** Sem post legado (R1) o item traz "post": null e NAO traz post_slug nem post_titulo, mas traz o espelho (D14); o IP sai decifrado. */
    @Test
    void itemSemPostLegadoOmiteChavesELevaOEspelho() throws Exception {
        Usuario ana = api.usuario("Ana Maria Souza", false);
        Comentario c = api.comentario(comentarios, ana, espelho, Instant.parse("2026-11-20T15:00:00Z"));
        c.setIpAddress("203.0.113.9");
        comentarios.saveAndFlush(c);
        ApiDoBlog.Sessao s = api.sessao(api.usuario("Admin", true));

        HttpResponse<String> r = api.enviar("GET", "/api/blog/admin/comments/" + c.getId() + "/", null, s);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode item = ApiDoBlog.JSON.readTree(r.body());
        assertThat(item.propertyNames()).containsExactly("id", "post", "customer", "customer_email", "customer_nome",
                "conteudo", "is_suspeito", "ip_address", "created_at", "updated_at", "post_espelho_slug",
                "post_espelho_titulo", "post_espelho_url");
        assertThat(item.get("post_espelho_slug").asString()).isEqualTo(espelho.getSlug());
        assertThat(item.get("post_espelho_titulo").asString()).isEqualTo(espelho.getTitulo());
        assertThat(item.get("post_espelho_url").asString()).isEqualTo(espelho.getUrl());
        assertThat(item.get("post").isNull()).isTrue();
        assertThat(item.get("ip_address").asString()).isEqualTo("203.0.113.9");
        assertThat(item.get("customer_nome").asString()).isEqualTo("Ana S.");
    }

    /** ?post=<slug> filtra so a FK legada: com o R1 nunca casa, nem com o slug de um espelho que existe. */
    @Test
    void filtroPostSoCasaALegadaEntaoNuncaCasa() throws Exception {
        Usuario ana = api.usuario("Ana", false);
        api.comentario(comentarios, ana, espelho, Instant.parse("2026-11-20T15:00:00Z"));
        ApiDoBlog.Sessao s = api.sessao(api.usuario("Admin", true));
        assertThat(api.enviar("GET", "/api/blog/admin/comments/?post=salmo-23", null, s).body())
                .isEqualTo("{\"count\":0,\"next\":null,\"previous\":null,\"results\":[]}");
        assertThat(ApiDoBlog.JSON.readTree(api.enviar("GET", "/api/blog/admin/comments/?post=", null, s).body())
                .get("count").asInt()).isEqualTo(1);
    }

    /** post_espelho=<slug>: igual exato, ultimo valor vence, slug desconhecido (ou so parecido) devolve pagina vazia. */
    @Test
    void filtroPostEspelhoCasaPeloSlug() throws Exception {
        PostEspelho outro = api.espelho(espelhos, "oracao-da-manha", PostEspelho.PUBLICADO);
        Usuario ana = api.usuario("Ana", false);
        Comentario noSalmo = api.comentario(comentarios, ana, espelho, Instant.parse("2026-11-20T15:00:00Z"));
        Comentario naOracao = api.comentario(comentarios, ana, outro, Instant.parse("2026-11-20T16:00:00Z"));
        ApiDoBlog.Sessao s = api.sessao(api.usuario("Admin", true));
        String base = "/api/blog/admin/comments/?post_espelho=";

        JsonNode salmo = ApiDoBlog.JSON.readTree(api.enviar("GET", base + "salmo-23", null, s).body());
        assertThat(salmo.get("count").asInt()).isEqualTo(1);
        assertThat(salmo.get("results").get(0).get("id").asString()).isEqualTo(noSalmo.getId().toString());
        JsonNode ultimo = ApiDoBlog.JSON.readTree(api.enviar("GET", base + "salmo-23&post_espelho=oracao-da-manha",
                null, s).body());
        assertThat(ultimo.get("results").get(0).get("id").asString()).isEqualTo(naOracao.getId().toString());
        for (String desconhecido : List.of("nao-existe", "salmo", "SALMO-23")) {
            assertThat(api.enviar("GET", base + desconhecido, null, s).body())
                    .isEqualTo("{\"count\":0,\"next\":null,\"previous\":null,\"results\":[]}");
        }
        assertThat(ApiDoBlog.JSON.readTree(api.enviar("GET", base, null, s).body()).get("count").asInt()).isEqualTo(2);
    }

    /** O 403 de quem nao e admin e o detail padrao do DRF (SEM_PERMISSAO), nao o texto do /api/admin/. */
    @Test
    void naoAdmin403DetailPadrao() throws Exception {
        Usuario ana = api.usuario("Ana", false);
        Comentario c = api.comentario(comentarios, ana, espelho, Instant.parse("2026-11-20T15:00:00Z"));
        ApiDoBlog.Sessao s = api.sessao(ana);
        for (String[] req : List.of(new String[] {"GET", "/api/blog/admin/comments/"},
                new String[] {"DELETE", "/api/blog/admin/comments/" + c.getId() + "/"},
                new String[] {"POST", "/api/blog/admin/comments/"})) {
            HttpResponse<String> r = api.enviar(req[0], req[1], null, s);
            assertThat(r.statusCode()).as(req[0]).isEqualTo(403);
            assertThat(r.body()).isEqualTo(PADRAO);
        }
        assertThat(comentarios.count()).isEqualTo(1);
        assertThat(api.enviar("GET", "/api/blog/admin/comments/", null, null).statusCode()).isEqualTo(401);
    }
}
