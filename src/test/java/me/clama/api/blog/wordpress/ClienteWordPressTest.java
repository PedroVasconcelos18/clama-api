package me.clama.api.blog.wordpress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Nunca chama o WordPress: HttpServer local. O formato das requisicoes e o gravado em contrato/blog. */
class ClienteWordPressTest {
    static final String UM_POST = "[{\"id\":201,\"slug\":\"novo-post\",\"title\":{\"raw\":\"Novo post\","
            + "\"rendered\":\"Novo post\"},\"status\":\"publish\",\"date_gmt\":\"2026-11-19T12:00:00\","
            + "\"link\":\"https://clama.me/blog/novo-post\",\"password\":\"\"}]";

    HttpServer server;
    final List<String> requisicoes = new CopyOnWriteArrayList<>();
    final List<String> autorizacoes = new CopyOnWriteArrayList<>();
    final List<String> accepts = new CopyOnWriteArrayList<>();
    final List<Duration> esperas = new ArrayList<>();
    final AtomicInteger chamadas = new AtomicInteger();
    volatile int[] statusPorChamada = {200};
    volatile String corpo = UM_POST;
    volatile String totalPaginas = null;

    @BeforeEach
    void sobe() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requisicoes.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + "?"
                    + ex.getRequestURI().getRawQuery());
            autorizacoes.add(ex.getRequestHeaders().getFirst("Authorization"));
            accepts.add(ex.getRequestHeaders().getFirst("Accept"));
            int n = chamadas.getAndIncrement();
            int st = statusPorChamada[Math.min(n, statusPorChamada.length - 1)];
            byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
            if (totalPaginas != null) ex.getResponseHeaders().add("X-WP-TotalPages", totalPaginas);
            ex.sendResponseHeaders(st, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    ClienteWordPress cliente() {
        return new ClienteWordPress(url() + "/", "clama-django-sync", "abcd EFGH ijkl MNOP qrst UVWX", esperas::add);
    }

    @Test
    void buscarPorSlugEnviaExatamenteARequisicaoGravadaNoContrato() {
        PostWp p = cliente().buscarPorSlug("novo-post").orElseThrow();
        assertThat(requisicoes).containsExactly("GET /wp-json/wp/v2/posts?slug=novo-post&status=any&context=edit"
                + "&per_page=1&_fields=id%2Cslug%2Ctitle%2Cstatus%2Cdate_gmt%2Clink%2Cpassword");
        assertThat(autorizacoes).containsExactly("Basic Y2xhbWEtZGphbmdvLXN5bmM6YWJjZEVGR0hpamtsTU5PUHFyc3RVVldY");
        assertThat(accepts).containsExactly("application/json");
        assertThat(p).isEqualTo(new PostWp(201, "novo-post", "Novo post", "Novo post", "publish",
                "2026-11-19T12:00:00", "https://clama.me/blog/novo-post", false));
        assertThat(p.publicadoEm()).isEqualTo(OffsetDateTime.parse("2026-11-19T12:00:00Z"));
    }

    @Test
    void listaVaziaEPostInexistente() {
        corpo = "[]";
        assertThat(cliente().buscarPorSlug("x")).isEmpty();
    }

    @Test
    void listarUsaPageEPerPage100ETotalDePaginasDoHeader() {
        totalPaginas = "3";
        PaginaWp pg = cliente().listar(2);
        assertThat(requisicoes).containsExactly("GET /wp-json/wp/v2/posts?page=2&per_page=100&status=any"
                + "&context=edit&_fields=id%2Cslug%2Ctitle%2Cstatus%2Cdate_gmt%2Clink%2Cpassword");
        assertThat(pg.totalPaginas()).isEqualTo(3);
        assertThat(pg.posts()).hasSize(1);
    }

    /** Item sem id numerico: a listagem o conta e segue (a reconciliacao pula); a busca por slug e indisponivel. */
    @Test
    void postSemIdNaListagemContaENaBuscaEIndisponivel() {
        corpo = "[{\"id\":\"abc\",\"slug\":\"a\"}, \"x\", {\"id\":7,\"slug\":\"b\"}]";
        PaginaWp pg = cliente().listar(1);
        assertThat(pg.posts()).extracting(PostWp::id).containsExactly(7L);
        assertThat(pg.semId()).isEqualTo(2);
        corpo = "[{\"slug\":\"a\"}]";
        assertThatThrownBy(() -> cliente().buscarPorSlug("a")).isInstanceOf(WordPressIndisponivel.class);
    }

    /** O espelho guarda o id em int: id negativo ou acima de Integer.MAX_VALUE conta como sem id, nao estoura depois. */
    @Test
    void idForaDoIntContaComoSemId() {
        corpo = "[{\"id\":2147483648,\"slug\":\"a\"}, {\"id\":-1,\"slug\":\"b\"}, {\"id\":2147483647,\"slug\":\"c\"},"
                + " {\"id\":0,\"slug\":\"d\"}]";
        PaginaWp pg = cliente().listar(1);
        assertThat(pg.posts()).extracting(PostWp::id).containsExactly(2147483647L, 0L);
        assertThat(pg.semId()).isEqualTo(2);
    }

    @Test
    void urlMalformadaViraIndisponivelNao500() {
        ClienteWordPress c = new ClienteWordPress("http://exemplo .com", "u", "s", esperas::add);
        assertThatThrownBy(() -> c.buscarPorSlug("x")).isInstanceOf(WordPressIndisponivel.class);
        assertThatThrownBy(() -> c.listar(1)).isInstanceOf(WordPressIndisponivel.class);
        assertThat(chamadas).hasValue(0);
    }

    @Test
    void totalDePaginasAusenteOuInvalidoEUm() {
        assertThat(cliente().listar(1).totalPaginas()).isEqualTo(1);
        totalPaginas = "abc";
        assertThat(cliente().listar(1).totalPaginas()).isEqualTo(1);
    }

    @Test
    void senhaDoPostEhBooleano() {
        corpo = "[{\"id\":1,\"slug\":\"a\",\"status\":\"publish\",\"password\":\"segredo\"}]";
        assertThat(cliente().buscarPorSlug("a").orElseThrow().protegidoPorSenha()).isTrue();
    }

    @Test
    void naoConfiguradoNaoChamaRede() {
        for (ClienteWordPress c : new ClienteWordPress[] {new ClienteWordPress("", "u", "s", esperas::add),
                new ClienteWordPress(url(), "", "s", esperas::add), new ClienteWordPress(url(), "u", "", esperas::add)}) {
            assertThat(c.configurado()).isFalse();
            assertThatThrownBy(() -> c.buscarPorSlug("x")).isInstanceOf(WordPressIndisponivel.class);
            assertThatThrownBy(() -> c.listar(1)).isInstanceOf(WordPressIndisponivel.class);
        }
        assertThat(chamadas).hasValue(0);
    }

    @Test
    void retry429NaoRetenta() {
        statusPorChamada = new int[] {429};
        assertThatThrownBy(() -> cliente().buscarPorSlug("x")).isInstanceOf(WordPressIndisponivel.class);
        assertThat(chamadas).hasValue(1);
        assertThat(esperas).isEmpty();
    }

    @Test
    void quatrocentosEOutros4xxNaoRetentam() {
        statusPorChamada = new int[] {401};
        assertThatThrownBy(() -> cliente().buscarPorSlug("x")).isInstanceOf(WordPressIndisponivel.class);
        assertThat(chamadas).hasValue(1);
    }

    @Test
    void cincoXxRetentaTresVezesComEsperasDeUmEDoisSegundos() {
        statusPorChamada = new int[] {503, 503, 200};
        assertThat(cliente().buscarPorSlug("novo-post")).isPresent();
        assertThat(chamadas).hasValue(3);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void cincoXxTresVezesEsgotaEVira400SemVazarHostNaMensagem() {
        statusPorChamada = new int[] {500};
        assertThatThrownBy(() -> cliente().buscarPorSlug("x")).isInstanceOfSatisfying(WordPressIndisponivel.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.corpo()).hasToString("{error={code=wordpress_indisponivel, message=Não foi possível ler a API do WordPress., "
                    + "pastoral_message=Estamos com dificuldade de falar com o blog agora.}}");
        });
        assertThat(chamadas).hasValue(3);
    }

    @Test
    void falhaDeRedeRetentaEDepoisIndisponivel() {
        server.stop(0);
        assertThatThrownBy(() -> cliente().buscarPorSlug("x")).isInstanceOf(WordPressIndisponivel.class);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void corpoNaoJsonOuNaoListaEIndisponivelNuncaZeroPosts() {
        corpo = "<html>manutencao</html>";
        assertThatThrownBy(() -> cliente().buscarPorSlug("x")).isInstanceOf(WordPressIndisponivel.class);
        corpo = "{\"id\":1}";
        assertThatThrownBy(() -> cliente().buscarPorSlug("x")).isInstanceOf(WordPressIndisponivel.class);
    }

    @Test
    void dataInvalidaOuVaziaViraNulo() {
        assertThat(new PostWp(1, "a", null, null, "publish", "ontem", "", false).publicadoEm()).isNull();
        assertThat(new PostWp(1, "a", null, null, "publish", "", "", false).publicadoEm()).isNull();
        assertThat(new PostWp(1, "a", null, null, "publish", null, "", false).publicadoEm()).isNull();
        assertThat(new PostWp(1, "a", null, null, "publish", "2026-11-19 12:00:00+03:00", "", false).publicadoEm())
                .isEqualTo(OffsetDateTime.parse("2026-11-19T09:00:00Z"));
    }
}
