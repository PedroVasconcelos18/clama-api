package me.clama.api.blog.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.blog.Comentario;
import me.clama.api.blog.ComentarioRepository;
import me.clama.api.blog.PostEspelho;
import me.clama.api.blog.PostEspelhoRepository;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP e dados dos testes do widget (comentarios, curtidas, /api/blog/comments/{id}/). Usuarios novos a cada teste (ids
 * nunca reaproveitados): o contador da janela fixa e por id e nao vaza entre testes.
 */
class ApiDoBlog {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String LIMPAR = "TRUNCATE blog_reacao, blog_comentario, blog_customerbanido, blog_postespelho CASCADE";

    private final int porta;
    private final JdbcTemplate jdbc;
    private final UsuarioRepository usuarios;
    private final ServicoDeTokens tokens;
    private final HttpClient http = HttpClient.newHttpClient();
    private int wp = 1000;

    ApiDoBlog(int porta, JdbcTemplate jdbc, UsuarioRepository usuarios, ServicoDeTokens tokens) {
        this.porta = porta;
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.tokens = tokens;
    }

    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute(LIMPAR);
    }

    Usuario usuario(String nome, boolean admin) {
        Usuario u = new Usuario();
        u.setEmail(nome + "-" + UUID.randomUUID() + "@x.com");
        u.setPassword("x");
        u.setNomeCompleto(nome);
        u.setClamaAdmin(admin);
        return usuarios.saveAndFlush(u);
    }

    PostEspelho espelho(PostEspelhoRepository espelhos, String slug, String status) {
        PostEspelho e = new PostEspelho();
        e.setWpPostId(wp++);
        e.setSlug(slug);
        e.setTitulo(slug);
        e.setStatus(status);
        return espelhos.save(e);
    }

    Comentario comentario(ComentarioRepository comentarios, Usuario u, PostEspelho e, Instant criadoEm) {
        Comentario c = new Comentario();
        c.setCustomer(u);
        c.setPostEspelho(e);
        c.setConteudo("Comentario de " + u.getNomeCompleto());
        c = comentarios.saveAndFlush(c);
        jdbc.update("update blog_comentario set created_at = ?, updated_at = ? where id = ?",
                java.sql.Timestamp.from(criadoEm), java.sql.Timestamp.from(criadoEm), c.getId());
        return c;
    }

    void banir(Usuario u, Usuario admin) {
        jdbc.update("insert into blog_customerbanido (id, created_at, updated_at, motivo, banido_em, banido_por_id,"
                + " customer_id) values (gen_random_uuid(), now(), now(), 'Spam', now(), ?, ?)", admin.getId(), u.getId());
    }

    record Sessao(String cookies, String csrf) {}

    Sessao sessao(Usuario u) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/csrf/")).build(),
                HttpResponse.BodyHandlers.ofString());
        String csrf = (String) JSON.readValue(r.body(), Map.class).get("csrf_token");
        return new Sessao("clama_access=" + tokens.emitirPar(u).access() + "; csrftoken=" + csrf, csrf);
    }

    /** s null = anonimo; corpo null = sem corpo. headers em pares nome, valor. */
    HttpResponse<String> enviar(String metodo, String path, String corpo, Sessao s, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).method(metodo,
                corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo));
        if (corpo != null) {
            b.header("Content-Type", "application/json");
        }
        if (s != null) {
            b.header("Cookie", s.cookies()).header("X-CSRFToken", s.csrf());
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + porta + path);
    }
}
