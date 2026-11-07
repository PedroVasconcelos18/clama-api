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
import me.clama.api.pedido.StatusPedido;
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

    @Test
    void statusRepetidoVaiComOUltimoValor() throws Exception {
        ApiDePedidos.semearCatalogo(jdbc);
        for (StatusPedido s : new StatusPedido[] {StatusPedido.PAGO, StatusPedido.ENVIADA}) {
            Pedido p = new Pedido();
            p.setPlano(planos.findById(UUID.fromString(ApiDePedidos.P1)).orElseThrow());
            p.setValorCentavos(2000);
            p.setStatus(s);
            pedidos.saveAndFlush(p);
        }
        JsonNode corpo = json.readTree(get("?status=pago&status=enviada").body());
        assertThat(corpo.get("count").asInt()).isEqualTo(1);
        assertThat(corpo.get("results").get(0).get("status").asString()).isEqualTo("enviada");
    }

    @Test
    void xForwardedProtoComListaEMaiusculas() throws Exception {
        ApiDePedidos.semearCatalogo(jdbc);
        for (int i = 0; i < 2; i++) {
            Pedido p = new Pedido();
            p.setPlano(planos.findById(UUID.fromString(ApiDePedidos.P1)).orElseThrow());
            p.setValorCentavos(2000);
            pedidos.saveAndFlush(p);
        }
        JsonNode corpo = json.readTree(get("?limit=1", "X-Forwarded-Proto", "HTTPS, http").body());
        assertThat(corpo.get("next").asString()).startsWith("https://");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plano=x", "created_after=2026-13-01", "created_before=abc", "user_id=abc"})
    void filtroMalformado400(String query) throws Exception {
        HttpResponse<String> r = get("?" + query);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get("detail").asString())
                .isEqualTo("Parâmetro " + query.split("=")[0] + " inválido.");
    }

    private Pedido pedido(String nome, String email, Usuario dono) {
        Pedido p = new Pedido();
        p.setPlano(planos.findById(UUID.fromString(ApiDePedidos.P1)).orElseThrow());
        p.setValorCentavos(2000);
        p.setNome(nome);
        p.setEmail(email);
        p.setUser(dono);
        return pedidos.saveAndFlush(p);
    }

    private Usuario cliente(String email, String nome) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setNomeCompleto(nome);
        u.setPassword("x");
        return usuarios.saveAndFlush(u);
    }

    private java.util.Set<UUID> achados(String q) throws Exception {
        JsonNode corpo = json.readTree(get("?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8))
                .body());
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        corpo.get("results").forEach(r -> ids.add(UUID.fromString(r.get("id").asString())));
        assertThat(corpo.get("count").asInt()).as("count de %s", q).isEqualTo(ids.size());
        return ids;
    }

    @Test
    void qAchaPorEmailENomeDaContaPrefixoDoIdENomeDecifradoSemConta() throws Exception {
        ApiDePedidos.semearCatalogo(jdbc);
        Usuario ana = cliente("ana.souza@exemplo.com", "Ana Souza");
        UUID deAna = pedido("Fulano", "fulano@exemplo.com", ana).getId();
        UUID deBia = pedido("Outro", "outro@exemplo.com", cliente("bia@exemplo.com", "Beatriz Lima")).getId();
        UUID semConta = pedido("Carla Dias", "carla@exemplo.com", null).getId();

        assertThat(achados("SOUZA@EXEMPLO")).containsExactly(deAna); // e-mail da conta, sem caixa
        assertThat(achados("beatriz")).containsExactly(deBia); // nome da conta
        assertThat(achados(deBia.toString().substring(0, 13))).containsExactly(deBia); // prefixo do id
        assertThat(achados(deBia.toString().substring(1, 13))).isEmpty(); // so prefixo, nao contem
        assertThat(achados("carla d")).containsExactly(semConta); // nome decifrado
        assertThat(achados("CARLA@exemplo")).containsExactly(semConta); // e-mail decifrado
        assertThat(achados("fulano")).isEmpty(); // PII do pedido de quem tem conta nao entra: vale a conta
        assertThat(achados("gAAAA")).isEmpty(); // o bug antigo: casava o texto cifrado
    }

    @Test
    void qEscapaCuringasEPaginaComCountCheio() throws Exception {
        ApiDePedidos.semearCatalogo(jdbc);
        UUID a = pedido("100% Fe", "a@exemplo.com", null).getId();
        pedido("1000 Fe", "b@exemplo.com", null);
        pedido("a_b", "c@exemplo.com", null);
        pedido("axb", "d@exemplo.com", null);
        pedido("back\\slash", "e@exemplo.com", null);
        assertThat(achados("100%")).containsExactly(a);
        assertThat(achados("a_b")).hasSize(1);
        assertThat(achados("\\")).hasSize(1);
        assertThat(achados("%")).containsExactly(a);
        // count = filtro inteiro, nao a pagina
        for (int i = 0; i < 3; i++) {
            pedido("Paula " + i, "paula" + i + "@exemplo.com", null);
        }
        JsonNode corpo = json.readTree(get("?q=paula&limit=2&offset=2").body());
        assertThat(corpo.get("count").asInt()).isEqualTo(3);
        assertThat(corpo.get("results")).hasSize(1);
    }
}
