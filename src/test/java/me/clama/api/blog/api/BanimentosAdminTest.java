package me.clama.api.blog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.blog.CustomerBanidoRepository;
import me.clama.api.blog.JanelaFixa;
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

/** /api/blog/admin/banned-customers/ (o resto esta nos cenarios blog_admin_bans_* do contrato). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class BanimentosAdminTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00Z");

    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired CustomerBanidoRepository banidos;
    @Autowired JanelaFixa janela;
    @Autowired RelogioDeTeste.Fixavel relogio;
    ApiDoBlog api;
    Usuario admin;
    ApiDoBlog.Sessao sessaoAdmin;

    @BeforeEach
    void prepara() throws Exception {
        janela.limpar();
        api = new ApiDoBlog(porta, jdbc, usuarios, tokens);
        api.limpar();
        relogio.fixar(AGORA);
        admin = api.usuario("Admin", true);
        sessaoAdmin = api.sessao(admin);
    }

    @AfterEach
    void limpa() {
        relogio.fixar(null);
        api.limpar();
    }

    private static String corpo(Usuario u) {
        return "{\"customer_id\": " + u.getId() + ", \"motivo\": \"Spam nos comentários\"}";
    }

    /** customer_id acima do int32 e so um customer que nao existe: 404 como no Django, nao o 400 de valor maximo. */
    @Test
    void customerIdAcimaDoInt32E404() throws Exception {
        HttpResponse<String> r = api.enviar("POST", "/api/blog/admin/banned-customers/",
                "{\"customer_id\": 5000000000, \"motivo\": \"Spam nos comentários\"}", sessaoAdmin);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
        assertThat(r.body()).contains("\"code\":\"customer_nao_encontrado\"");
    }

    @Test
    void naoBaneAdmin400() throws Exception {
        Usuario outro = api.usuario("Outro Admin", true);
        for (Usuario alvo : List.of(outro, admin)) { // inclusive o proprio
            HttpResponse<String> r = api.enviar("POST", "/api/blog/admin/banned-customers/", corpo(alvo), sessaoAdmin);
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).isEqualTo("{\"code\":\"cannot_ban_admin\",\"pastoral_message\":\"Admins não podem ser"
                    + " banidos. Para remover acesso, edite a flag is_clama_admin diretamente.\"}");
        }
        assertThat(banidos.count()).isZero();
    }

    @Test
    void revogarSemBan404() throws Exception {
        Usuario ana = api.usuario("Ana", false);
        HttpResponse<String> r = api.enviar("DELETE", "/api/blog/admin/banned-customers/" + ana.getId() + "/", null,
                sessaoAdmin);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).isEmpty();
        // id gigante nao casa ninguem (e nao vira 500)
        assertThat(api.enviar("DELETE", "/api/blog/admin/banned-customers/99999999999999999999999/", null, sessaoAdmin)
                .statusCode()).isEqualTo(404);
        // letras: nem rota existe (404 de URL, antes de olhar o banco)
        assertThat(api.enviar("DELETE", "/api/blog/admin/banned-customers/undefined/", null, sessaoAdmin)
                .statusCode()).isEqualTo(404);
    }

    @Test
    void naoAdmin403DetailPadrao() throws Exception {
        Usuario ana = api.usuario("Ana", false);
        ApiDoBlog.Sessao s = api.sessao(ana);
        for (String[] req : List.of(new String[] {"GET", "/api/blog/admin/banned-customers/", null},
                new String[] {"POST", "/api/blog/admin/banned-customers/", corpo(ana)},
                new String[] {"DELETE", "/api/blog/admin/banned-customers/" + ana.getId() + "/", null})) {
            HttpResponse<String> r = api.enviar(req[0], req[1], req[2], s);
            assertThat(r.statusCode()).as(req[0]).isEqualTo(403);
            assertThat(r.body()).isEqualTo(ComentariosAdminTest.PADRAO);
        }
        assertThat(banidos.count()).isZero();
    }

    /** R9: POSTs simultaneos deixam um ban ativo so (um 201, o resto 200), e revogar libera o customer. */
    @Test
    void banConcorrente() throws Exception {
        Usuario ana = api.usuario("Ana", false);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    largada.await();
                    return api.enviar("POST", "/api/blog/admin/banned-customers/", corpo(ana), sessaoAdmin)
                            .statusCode();
                }));
            }
            largada.countDown();
            List<Integer> status = new ArrayList<>();
            for (Future<Integer> f : fs) {
                status.add(f.get());
            }
            assertThat(status).containsOnly(200, 201).filteredOn(x -> x == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(banidos.countByRevogadoEmIsNull()).isEqualTo(1);

        HttpResponse<String> r = api.enviar("DELETE", "/api/blog/admin/banned-customers/" + ana.getId() + "/", null,
                sessaoAdmin);
        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(banidos.existsByCustomer_IdAndRevogadoEmIsNull(ana.getId())).isFalse();
        assertThat(jdbc.queryForObject("select revogado_em = '2026-11-20T15:00:00Z' and revogado_por_id = ? from"
                + " blog_customerbanido", Boolean.class, admin.getId())).isTrue();
        // livre de novo: um ban novo, e o antigo segue revogado
        assertThat(api.enviar("POST", "/api/blog/admin/banned-customers/", corpo(ana), sessaoAdmin).statusCode())
                .isEqualTo(201);
        assertThat(banidos.count()).isEqualTo(2);
    }
}
