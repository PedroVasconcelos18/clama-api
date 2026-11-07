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

/** GET/POST /api/blog/posts/{slug}/comments/ (o resto esta nos cenarios blog_comentarios_* do contrato). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class ComentariosTest {
    static final String ROTA = "/api/blog/posts/salmo-23/comments/";
    static final String BANIDO = "{\"code\":\"customer_banido\",\"pastoral_message\":\"Sua conta foi suspensa do sistema de"
            + " comentários. Entre em contato pelo nosso e-mail de suporte se quiser entender o motivo.\"}";

    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PostEspelhoRepository espelhos;
    @Autowired ComentarioRepository comentarios;
    @Autowired RelogioDeTeste.Fixavel relogio;
    ApiDoBlog api;

    @BeforeEach
    void prepara() {
        api = new ApiDoBlog(porta, jdbc, usuarios, tokens);
        api.limpar();
        api.espelho(espelhos, "salmo-23", PostEspelho.PUBLICADO);
        relogio.fixar(Instant.parse("2026-11-20T15:00:30Z"));
    }

    @AfterEach
    void limpa() {
        relogio.fixar(null);
        api.limpar();
    }

    @Test
    void sextoComentario429ComCorpoPlano() throws Exception {
        ApiDoBlog.Sessao ana = api.sessao(api.usuario("Ana Souza", false));
        assertThat(api.enviar("POST", ROTA, "{\"conteudo\": \"Amém, irmãos\"}", ana).statusCode()).isEqualTo(201);
        for (int i = 0; i < 4; i++) { // invalido tambem conta
            assertThat(api.enviar("POST", ROTA, "{\"conteudo\": \"a\"}", ana).statusCode()).isEqualTo(400);
        }
        // o 429 sai antes da validacao: corpo invalido no 6o pedido tambem e 429
        for (String corpo : new String[] {"{\"conteudo\": \"Mais um\"}", "[]"}) {
            HttpResponse<String> r = api.enviar("POST", ROTA, corpo, ana);
            assertThat(r.statusCode()).isEqualTo(429);
            assertThat(r.body()).isEqualTo("{\"code\":\"rate_limit_exceeded\",\"pastoral_message\":\"Calma! Aguarde um"
                    + " momento antes de comentar de novo.\"}");
            assertThat(r.headers().firstValue("Retry-After")).isEmpty();
        }
        // a janela vira (o fim tem jitter por usuario, no maximo 60 s a frente) e o contador recomeca
        relogio.fixar(Instant.parse("2026-11-20T15:01:31Z"));
        assertThat(api.enviar("POST", ROTA, "{\"conteudo\": \"De novo\"}", ana).statusCode()).isEqualTo(201);
        // outro usuario tem o seu contador; a curtida, o seu grupo
        ApiDoBlog.Sessao beto = api.sessao(api.usuario("Beto", false));
        assertThat(api.enviar("POST", ROTA, "{\"conteudo\": \"Do Beto\"}", beto).statusCode()).isEqualTo(201);
    }

    @Test
    void banidoRecebe403Plano() throws Exception {
        Usuario admin = api.usuario("Admin", true);
        Usuario beto = api.usuario("Beto", false);
        api.banir(beto, admin);
        ApiDoBlog.Sessao s = api.sessao(beto);
        for (String rota : new String[] {ROTA, "/api/blog/posts/salmo-23/like/"}) {
            HttpResponse<String> r = api.enviar("POST", rota, "{\"conteudo\": \"Amém!\"}", s);
            assertThat(r.statusCode()).as(rota).isEqualTo(403);
            assertThat(r.body()).as(rota).isEqualTo(BANIDO);
        }
        // banimento revogado nao barra
        jdbc.update("update blog_customerbanido set revogado_em = now() where customer_id = ?", beto.getId());
        assertThat(api.enviar("POST", ROTA, "{\"conteudo\": \"Amém!\"}", s).statusCode()).isEqualTo(201);
    }

    @Test
    void adminBanidoPassa() throws Exception {
        Usuario admin = api.usuario("Admin Clama", true);
        api.banir(admin, admin);
        HttpResponse<String> r = api.enviar("POST", ROTA, "{\"conteudo\": \"Comentário do admin\"}", api.sessao(admin));
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(ApiDoBlog.JSON.readTree(r.body()).get("customer_nome").asString()).isEqualTo("Admin C.");
    }

    @Test
    void erroDeConteudoComDictNoCampo() throws Exception {
        ApiDoBlog.Sessao ana = api.sessao(api.usuario("Ana", false));
        HttpResponse<String> r = api.enviar("POST", ROTA, "{\"conteudo\": \" ab \"}", ana);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"conteudo\":{\"code\":\"comentario_invalido\",\"pastoral_message\":\"Esse"
                + " comentário precisa de um pouquinho mais de texto pra ser publicado.\"}}");
        // os outros erros do mesmo campo continuam em lista
        r = api.enviar("POST", ROTA, "{\"conteudo\": \"   \"}", ana);
        assertThat(r.body()).isEqualTo("{\"conteudo\":[\"Este campo pode não estar em branco.\"]}");
        r = api.enviar("POST", ROTA, "{\"conteudo\": \" " + "x".repeat(2001) + " \"}", ana);
        assertThat(r.body()).isEqualTo(
                "{\"conteudo\":[\"Certifique-se de que este campo não tenha mais de 2000 caracteres.\"]}");
        assertThat(jdbc.queryForObject("select count(*) from blog_comentario", Long.class)).isZero();
    }

    @Test
    void ipCruDoPrimeiroItemDoXffSemValidar() throws Exception {
        ApiDoBlog.Sessao ana = api.sessao(api.usuario("Ana", false));
        assertThat(api.enviar("POST", ROTA, "{\"conteudo\": \"Amém!\"}", ana, "X-Forwarded-For", " nao-e-ip , 10.0.0.1")
                .statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select ip_address from blog_comentario", String.class)).startsWith("gAAAAA");
        assertThat(comentarios.findAll()).singleElement().extracting(Comentario::getIpAddress).isEqualTo("nao-e-ip");
    }

    @Test
    void cacheControlPublicoNoGetInclusiveNosErros() throws Exception {
        String publico = "public, max-age=10";
        assertThat(api.enviar("GET", ROTA, null, null).headers().firstValue("Cache-Control")).hasValue(publico);
        HttpResponse<String> semEspelho = api.enviar("GET", "/api/blog/posts/nao-existe/comments/", null, null);
        assertThat(semEspelho.statusCode()).isEqualTo(404);
        assertThat(semEspelho.body()).isEqualTo("{\"detail\":\"Não encontrado.\"}");
        assertThat(semEspelho.headers().firstValue("Cache-Control")).hasValue(publico);
        HttpResponse<String> cookieRuim = api.enviar("GET", ROTA, null, null, "Cookie", "clama_access=lixo");
        assertThat(cookieRuim.statusCode()).isEqualTo(401);
        assertThat(cookieRuim.headers().firstValue("Cache-Control")).hasValue(publico);
        assertThat(api.enviar("GET", ROTA + "?page=9", null, null).headers().firstValue("Cache-Control"))
                .hasValue(publico);
        // a escrita continua com o no-store padrao
        HttpResponse<String> anonimo = api.enviar("POST", ROTA, "{\"conteudo\": \"Amém!\"}", null);
        assertThat(anonimo.statusCode()).isEqualTo(401);
        assertThat(anonimo.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        // o Django (handler pastoral) responde o 401 de anonimo sem WWW-Authenticate (conferido no .venv)
        assertThat(anonimo.headers().firstValue("WWW-Authenticate")).isEmpty();
    }
}
