package me.clama.api.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** O que o contrato gravado nao cobre no admin de clientes: contagem de nao gratuitos e ban revogado. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class ClientesAdminTest {
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
    Usuario admin;

    @BeforeEach
    void admin() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("delete from orders_pedido");
        jdbc.update("delete from blog_customerbanido");
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        Usuario u = new Usuario();
        u.setEmail("admin-clientes@exemplo.com");
        u.setPassword("x");
        u.setClamaAdmin(true);
        admin = usuarios.saveAndFlush(u);
        cookie = "clama_access=" + tokens.emitirPar(admin).access();
    }

    private JsonNode get(String caminho) throws Exception {
        return json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta
                + "/api/admin/customers/" + caminho)).header("Cookie", cookie).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body());
    }

    private Usuario cliente(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("x");
        return usuarios.saveAndFlush(u);
    }

    private void ban(Usuario u, String motivo, boolean revogado) {
        jdbc.update("""
                insert into blog_customerbanido (created_at, updated_at, id, motivo, banido_em, revogado_em,
                    banido_por_id, customer_id) values (now(), now(), ?::uuid, ?, now(), %s, ?, ?)"""
                .formatted(revogado ? "now()" : "null"), UUID.randomUUID().toString(), motivo, admin.getId(),
                u.getId());
    }

    @Test
    void pedidosPagosContaNaoGratuitos() throws Exception {
        String plano = "55555555-5555-4555-8555-000000000001";
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade,
                    ordem, ativo, visivel) values (now(), now(), ?::uuid, 'P', 2000, 'd', 'simples', 1, true, true)
                on conflict (id) do nothing""", plano);
        Usuario u = cliente("a@exemplo.com");
        for (boolean gratuito : new boolean[] {false, true}) {
            Pedido p = new Pedido(); // status padrao: aguardando_pagamento
            p.setPlano(planos.findById(UUID.fromString(plano)).orElseThrow());
            p.setUser(u);
            p.setEhGratuito(gratuito);
            pedidos.saveAndFlush(p);
        }
        JsonNode d = get(u.getId() + "/");
        assertThat(d.get("total_pedidos").asInt()).isEqualTo(2);
        assertThat(d.get("pedidos_pagos").asInt()).isEqualTo(1);
        assertThat(d.get("pedidos_gratuitos").asInt()).isEqualTo(1);
    }

    @Test
    void banRevogadoNaoConta() throws Exception {
        Usuario u = cliente("b@exemplo.com");
        ban(u, "Ofensa", true);
        JsonNode lista = get("?q=b@exemplo");
        assertThat(lista.get("results").get(0).get("is_banned").asBoolean()).isFalse();
        assertThat(lista.get("results").get(0).get("motivo_ban").isNull()).isTrue();
        assertThat(get("?banned=true").get("count").asInt()).isZero();
        JsonNode d = get(u.getId() + "/");
        assertThat(d.get("is_banned").asBoolean()).isFalse();
        assertThat(d.get("banimentos")).hasSize(1);
    }
}
