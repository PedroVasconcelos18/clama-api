package me.clama.api.pedido.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** O que o contrato gravado nao cobre das metricas: os limites exatos da janela, o pct e os headers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class MetricasAdminTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00.250Z");

    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired RelogioDeTeste.Fixavel relogio;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    String cookie;

    @BeforeEach
    void admin() {
        ApiDePedidos.apagarPedidos(jdbc);
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        ApiDePedidos.semearCatalogo(jdbc);
        Usuario u = new Usuario();
        u.setEmail("admin-metricas@exemplo.com");
        u.setPassword("x");
        u.setClamaAdmin(true);
        u.setActive(true);
        cookie = "clama_access=" + tokens.emitirPar(usuarios.saveAndFlush(u)).access();
        relogio.fixar(AGORA);
    }

    @Test
    void janelasExatas() {
        var dia = MetricasAdminController.janela("day", AGORA);
        assertThat(dia.start()).isEqualTo("2026-11-19T15:00:00.250000+00:00");
        assertThat(dia.end()).isEqualTo("2026-11-20T15:00:00.250000+00:00");
        var semana = MetricasAdminController.janela("week", Instant.parse("2026-11-20T15:00:00Z"));
        assertThat(semana.start()).isEqualTo("2026-11-13T15:00:00+00:00");
        assertThat(semana.end()).isEqualTo("2026-11-20T15:00:00+00:00");
        var mes = MetricasAdminController.janela("month", AGORA);
        assertThat(mes.start()).isEqualTo("2026-11-01T00:00:00+00:00");
        assertThat(mes.end()).isEqualTo("2026-11-30T23:59:59+00:00");
        // dezembro vira o ano
        assertThat(MetricasAdminController.janela("month", Instant.parse("2026-12-05T00:00:00Z")).end())
                .isEqualTo("2026-12-31T23:59:59+00:00");
        assertThat(MetricasAdminController.janela("year", AGORA)).isNull();
    }

    @Test
    void pctArredondaComoPython() {
        assertThat(MetricasAdminController.pct(1, 8)).isEqualTo(12.5);
        assertThat(MetricasAdminController.pct(1, 3)).isEqualTo(33.3);
        assertThat(MetricasAdminController.pct(1, 16)).isEqualTo(6.2); // 6.25 exato: half-even
        assertThat(MetricasAdminController.pct(3, 3)).isEqualTo(100.0);
    }

    @Test
    void mesTerminaNoSegundoCheio() throws Exception {
        pedido("2026-11-30T23:59:59.000Z");
        pedido("2026-11-30T23:59:59.500Z"); // fora: o Django perde os microssegundos finais
        pedido("2026-12-01T00:00:00Z");
        var corpo = json.readTree(get("overview/?period=month").body());
        assertThat(corpo.get("pedidos_total").asInt()).isEqualTo(1);
    }

    @Test
    void cacheControl60SoNo200() throws Exception {
        HttpResponse<String> ok = get("overview/?period=day");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Cache-Control")).contains("max-age=60");
        assertThat(ok.headers().firstValue("Expires")).contains("Fri, 20 Nov 2026 15:01:00 GMT");
        HttpResponse<String> ruim = get("overview/?period=year");
        assertThat(ruim.statusCode()).isEqualTo(400);
        assertThat(ruim.headers().firstValue("Cache-Control")).isEmpty();
        assertThat(ruim.headers().firstValue("Expires")).isEmpty();
    }

    private HttpResponse<String> get(String caminho) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/admin/metrics/" + caminho))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void pedido(String criadoEm) {
        Pedido p = new Pedido();
        p.setPlano(planos.findById(UUID.fromString(ApiDePedidos.P1)).orElseThrow());
        p.setValorCentavos(2000);
        p.setStatus(StatusPedido.PAGO);
        p = pedidos.saveAndFlush(p);
        jdbc.update("update orders_pedido set created_at = ?::timestamptz where id = ?", criadoEm, p.getId());
    }
}
