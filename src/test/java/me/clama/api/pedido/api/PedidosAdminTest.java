package me.clama.api.pedido.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** O que o contrato gravado nao cobre: links atras do proxy e filtro malformado (o Django da 500, o Java 400). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class PedidosAdminTest {
    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    String cookie;

    @BeforeEach
    void admin() {
        ApiDePedidos.apagarPedidos(jdbc);
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        Usuario u = new Usuario();
        u.setEmail("admin-pedidos@exemplo.com");
        u.setPassword("x");
        u.setClamaAdmin(true);
        u.setActive(true);
        cookie = "clama_access=" + tokens.emitirPar(usuarios.saveAndFlush(u)).access();
    }

    private HttpResponse<String> get(String query, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/admin/pedidos/"
                + query)).header("Cookie", cookie);
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void linksSaemEmHttpsComXForwardedProto() throws Exception {
        ApiDePedidos.semearCatalogo(jdbc);
        for (int i = 0; i < 2; i++) {
            Pedido p = new Pedido();
            p.setPlano(planos.findById(UUID.fromString(ApiDePedidos.P1)).orElseThrow());
            p.setValorCentavos(2000);
            pedidos.saveAndFlush(p);
        }
        JsonNode corpo = json.readTree(get("?limit=1", "X-Forwarded-Proto", "https").body());
        assertThat(corpo.get("next").asString()).startsWith("https://localhost:").endsWith("/api/admin/pedidos/?limit=1&offset=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plano=x", "created_after=2026-13-01", "created_before=abc", "user_id=abc"})
    void filtroMalformado400(String query) throws Exception {
        HttpResponse<String> r = get("?" + query);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get("detail").asString())
                .isEqualTo("Parâmetro " + query.split("=")[0] + " inválido.");
    }
}
