package me.clama.api.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Corridas que o contrato gravado (sequencial) nao cobre: ativacao e criacao concorrentes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class PromptAdminTest {
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
        jdbc.update("delete from prompts_prompttemplate");
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        Usuario u = new Usuario();
        u.setEmail("admin-prompts@exemplo.com");
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
        csrf = r.body().replaceAll(".*\"csrf_token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        String cookieCsrf = r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("csrftoken="))
                .findFirst().orElseThrow().split(";")[0];
        cookies = access + "; " + cookieCsrf;
    }

    HttpResponse<String> post(String caminho, String corpo) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Cookie", cookies).header("X-CSRFToken", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    <T> List<T> emParalelo(List<Callable<T>> tarefas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<T>> futuros = new ArrayList<>();
        for (Callable<T> t : tarefas) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return t.call();
            }));
        }
        largada.countDown();
        List<T> r = new ArrayList<>();
        for (Future<T> f : futuros) {
            r.add(f.get());
        }
        pool.shutdown();
        return r;
    }

    @Autowired
    PromptTemplateRepository prompts;

    @Test
    void buscarAtivoVazioUmOuVarios() {
        assertThat(prompts.buscarAtivo()).isEmpty();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String sql = "insert into prompts_prompttemplate (id, created_at, updated_at, nome, versao, system_prompt,"
                + " instrucoes_por_complexidade, ativo) values (?, now(), now(), ?, ?, 's', '{}', true)";
        jdbc.update(sql, a, "ativo-a", 1);
        assertThat(prompts.buscarAtivo()).hasValueSatisfying(p -> assertThat(p.getId()).isEqualTo(a));
        jdbc.update(sql, b, "ativo-b", 2);
        org.assertj.core.api.Assertions.assertThatThrownBy(prompts::buscarAtivo)
                .hasRootCauseInstanceOf(IllegalStateException.class) // o proxy do repositorio embrulha em DataAccessException
                .hasRootCauseMessage("mais de um PromptTemplate ativo");
    }

    @Test
    void ativarConcorrenteDeixaUmAtivo() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            jdbc.update("insert into prompts_prompttemplate (id, created_at, updated_at, nome, versao, system_prompt,"
                    + " instrucoes_por_complexidade, ativo) values (?, now(), now(), ?, ?, 's', '{}', ?)", id,
                    "p" + i, i, i == 1);
        }
        for (int rodada = 0; rodada < 5; rodada++) {
            List<Callable<Integer>> tarefas = new ArrayList<>();
            for (UUID id : ids) {
                tarefas.add(() -> post("/api/admin/prompts/" + id + "/ativar/", "").statusCode());
            }
            assertThat(emParalelo(tarefas)).allMatch(s -> s == 200);
            assertThat(jdbc.queryForObject("select count(*) from prompts_prompttemplate where ativo", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void criacaoConcorrenteDoMesmoNomeNovoDaUm201EOsOutros400() throws Exception {
        String corpo = "{\"nome\":\"corrida\",\"system_prompt\":\"s\"}";
        List<Callable<HttpResponse<String>>> tarefas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tarefas.add(() -> post("/api/admin/prompts/", corpo));
        }
        List<HttpResponse<String>> r = emParalelo(tarefas);
        assertThat(r.stream().filter(x -> x.statusCode() == 201)).hasSize(1);
        assertThat(r.stream().filter(x -> x.statusCode() == 400)).hasSize(7)
                .allMatch(x -> x.body().equals("{\"nome\":[\"Template de Prompt com este Nome já existe.\"]}"));
        assertThat(jdbc.queryForObject("select count(*) from prompts_prompttemplate", Integer.class)).isEqualTo(1);
    }

    @Test
    void jsonbRedondaJsonArbitrarioEPreviewAindaNaoExiste() throws Exception {
        HttpResponse<String> r = post("/api/admin/prompts/",
                "{\"nome\":\"j\",\"system_prompt\":\"s\",\"instrucoes_por_complexidade\":[1,{\"b\":null,\"a\":[true,\"é\"]}]}");
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).contains("\"instrucoes_por_complexidade\":[1,{\"b\":null,\"a\":[true,\"é\"]}]");
        UUID id = jdbc.queryForObject("select id from prompts_prompttemplate", UUID.class);
        assertThat(post("/api/admin/prompts/" + id + "/preview/", "{}").statusCode()).isEqualTo(404);
    }
}
