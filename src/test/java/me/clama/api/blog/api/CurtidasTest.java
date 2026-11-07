package me.clama.api.blog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
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

/** POST /api/blog/posts/{slug}/like/ (o resto esta nos cenarios blog_like_* do contrato). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class CurtidasTest {
    static final String ROTA = "/api/blog/posts/salmo-23/like/";

    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PostEspelhoRepository espelhos;
    @Autowired RelogioDeTeste.Fixavel relogio;
    @Autowired DataSource dados;
    @Autowired JanelaFixa janela;
    ApiDoBlog api;

    @BeforeEach
    void prepara() {
        janela.limpar();
        api = new ApiDoBlog(porta, jdbc, usuarios, tokens);
        api.limpar();
        api.espelho(espelhos, "salmo-23", PostEspelho.PUBLICADO);
        relogio.fixar(Instant.parse("2026-11-20T15:00:00Z")); // a janela fixa nao vira no meio do teste
    }

    @AfterEach
    void limpa() {
        relogio.fixar(null);
        api.limpar();
    }

    /**
     * R7: o 2o INSERT da mesma curtida chega enquanto o 1o ainda nao commitou. No Django ele estoura o unique (500);
     * aqui o ON CONFLICT DO NOTHING espera o 1o, nao grava nada e responde 200 com uma curtida so. Deterministico: o
     * teste segura o 1o INSERT numa transacao aberta ate o pedido ficar parado no lock do indice unico.
     */
    @Test
    void curtidaConcorrente() throws Exception {
        Usuario ana = api.usuario("Ana", false);
        ApiDoBlog.Sessao s = api.sessao(ana);
        UUID espelho = jdbc.queryForObject("select id from blog_postespelho where slug = 'salmo-23'", UUID.class);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection primeiro = dados.getConnection()) {
            primeiro.setAutoCommit(false);
            try (PreparedStatement ins = primeiro.prepareStatement("insert into blog_reacao (id, created_at, updated_at,"
                    + " tipo, customer_id, post_espelho_id) values (gen_random_uuid(), now(), now(), 'like', ?, ?)")) {
                ins.setLong(1, ana.getId());
                ins.setObject(2, espelho);
                ins.executeUpdate();
            }
            Future<HttpResponse<String>> clique = pool.submit(() -> api.enviar("POST", ROTA, null, s));
            long limite = System.nanoTime() + 10_000_000_000L;
            while (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                    + " and query ilike 'INSERT INTO blog_reacao%'", Long.class) == 0) {
                assertThat(System.nanoTime()).as("o pedido nao chegou a esperar o lock").isLessThan(limite);
                Thread.sleep(20);
            }
            primeiro.commit();
            HttpResponse<String> r = clique.get(10, TimeUnit.SECONDS);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(r.body()).isEqualTo("{\"liked\":true,\"like_count\":1}");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from blog_reacao", Long.class)).isOne();
    }

    /** Dois cliques soltos ao mesmo tempo: nunca 500 nem curtida duplicada, e o estado final bate com as respostas. */
    @Test
    void doisCliquesSimultaneosNuncaDao500() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int rodada = 0; rodada < 10; rodada++) {
                Usuario u = api.usuario("Ana " + rodada, false);
                ApiDoBlog.Sessao s = api.sessao(u);
                CountDownLatch largada = new CountDownLatch(1);
                Callable<HttpResponse<String>> clique = () -> {
                    largada.await();
                    return api.enviar("POST", ROTA, null, s);
                };
                List<Future<HttpResponse<String>>> f = List.of(pool.submit(clique), pool.submit(clique));
                largada.countDown();
                int curtidas = 0;
                for (Future<HttpResponse<String>> r : f) {
                    assertThat(r.get().statusCode()).as("rodada %d: %s", rodada, r.get().body()).isEqualTo(200);
                    curtidas += ApiDoBlog.JSON.readTree(r.get().body()).get("liked").asBoolean() ? 1 : 0;
                }
                // os dois curtiram (simultaneos: ON CONFLICT) = 1 linha; um curtiu e o outro desfez (em sequencia) = 0
                long linhas = jdbc.queryForObject("select count(*) from blog_reacao where customer_id = ?", Long.class,
                        u.getId());
                assertThat(linhas).as("rodada %d", rodada).isEqualTo(curtidas == 2 ? 1 : 0);
                assertThat(curtidas).isPositive();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void alternaEContaSoOPost() throws Exception {
        ApiDoBlog.Sessao ana = api.sessao(api.usuario("Ana", false));
        ApiDoBlog.Sessao beto = api.sessao(api.usuario("Beto", false));
        api.espelho(espelhos, "outro", PostEspelho.PUBLICADO);
        assertThat(api.enviar("POST", "/api/blog/posts/outro/like/", null, beto).body())
                .isEqualTo("{\"liked\":true,\"like_count\":1}");
        assertThat(api.enviar("POST", ROTA, null, beto).body()).isEqualTo("{\"liked\":true,\"like_count\":1}");
        assertThat(api.enviar("POST", ROTA, null, ana).body()).isEqualTo("{\"liked\":true,\"like_count\":2}");
        assertThat(api.enviar("POST", ROTA, null, ana).body()).isEqualTo("{\"liked\":false,\"like_count\":1}");
        HttpResponse<String> get = api.enviar("GET", ROTA, null, ana);
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(api.enviar("GET", ROTA, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void trigesimoPrimeiro429ComCorpoPlano() throws Exception {
        ApiDoBlog.Sessao ana = api.sessao(api.usuario("Ana", false));
        for (int i = 0; i < 30; i++) {
            assertThat(api.enviar("POST", ROTA, null, ana).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> r = api.enviar("POST", ROTA, null, ana);
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.body()).isEqualTo("{\"code\":\"rate_limit_exceeded\",\"pastoral_message\":\"Calma! Aguarde um"
                + " momento antes de reagir de novo.\"}");
    }
}
