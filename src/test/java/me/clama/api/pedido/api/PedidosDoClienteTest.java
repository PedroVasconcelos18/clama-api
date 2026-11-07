package me.clama.api.pedido.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class PedidosDoClienteTest {
    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired RateLimiter limiter;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    final String sufixo = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void prepara() {
        limiter.limpar();
        ApiDePedidos.apagarPedidos(jdbc);
        ApiDePedidos.semearCatalogo(jdbc);
    }

    @AfterEach
    void limpa() {
        ApiDePedidos.apagarPedidos(jdbc); // o pedido prende o usuario por FK; outras classes apagam users_user
    }

    private Usuario novo(String nome, boolean trocarSenha) {
        Usuario u = new Usuario();
        u.setEmail(nome + "-" + sufixo + "@x.com");
        u.setPassword("x");
        u.setForceChangePassword(trocarSenha);
        return usuarios.saveAndFlush(u);
    }

    private void pedido(Usuario u, String criadoEm) {
        Pedido p = new Pedido();
        p.setUser(u);
        p.setPlano(planos.findById(UUID.fromString(ApiDePedidos.P1)).orElseThrow());
        p.setValorCentavos(2000);
        p = pedidos.saveAndFlush(p);
        jdbc.update("update orders_pedido set created_at = ?::timestamptz where id = ?", criadoEm, p.getId());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listar(Usuario u, String query) throws Exception {
        String access = tokens.emitirPar(u).access();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta
                + "/api/customer/pedidos/" + query)).header("Cookie", "clama_access=" + access).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readValue(r.body(), List.class);
    }

    @Test
    void soOsPedidosDoDonoMesmoComSenhaTemporaria() throws Exception {
        Usuario ana = novo("ana", true);
        pedido(ana, "2026-11-02T12:00:00Z");
        pedido(novo("beto", false), "2026-11-02T12:00:00Z");
        assertThat(listar(ana, "")).hasSize(1);
    }

    @Test
    void dataNosDoisFormatosELimitesInclusivosEmSaoPaulo() throws Exception {
        Usuario ana = novo("ana", false);
        pedido(ana, "2026-11-01T02:59:59Z"); // 31/10 23:59:59 em Sao Paulo
        pedido(ana, "2026-11-01T03:00:00Z"); // 01/11 00:00:00
        pedido(ana, "2026-11-02T02:59:59.999999Z"); // 01/11 23:59:59.999999
        pedido(ana, "2026-11-02T03:00:00Z");
        assertThat(listar(ana, "?from=2026-11-01&to=2026-11-01")).hasSize(2);
        assertThat(listar(ana, "?from=20261101&to=20261101")).hasSize(2);
        assertThat(listar(ana, "?from=2026-13-01&to=abc")).hasSize(4);
    }
}
