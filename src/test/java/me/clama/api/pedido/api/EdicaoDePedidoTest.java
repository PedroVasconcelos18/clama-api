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
import me.clama.api.oracao.AlertaDeErro;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PATCH /api/admin/pedidos/{id}/ (mapa 7 §7.3): so os campos do PedidoAdmin, auditoria, alerta de erro, 400/401/403/404. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class EdicaoDePedidoTest {
    static final String P2 = "55555555-5555-4555-8555-000000000002";

    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired AlertaDeErro alerta;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    long adminId;
    String cookie;
    String cookieComum;
    String csrf;
    UUID id;

    @BeforeEach
    void preparar() throws Exception {
        ApiDePedidos.apagarPedidos(jdbc);
        jdbc.update("delete from django_admin_log");
        jdbc.update("delete from scheduled_tasks");
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        alerta.limpar();
        ApiDePedidos.semearCatalogo(jdbc);
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem,
                    ativo, visivel) values (now(), now(), ?::uuid, 'Plano Antigo 2', 5000, 'd', 'simples', 2, false, true)
                on conflict (id) do nothing""", P2);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/csrf/")).build(),
                HttpResponse.BodyHandlers.ofString());
        csrf = json.readTree(r.body()).get("csrf_token").asString();
        Usuario a = new Usuario();
        a.setEmail("admin-edicao@exemplo.com");
        a.setPassword("x");
        a.setClamaAdmin(true);
        a.setActive(true);
        a = usuarios.saveAndFlush(a);
        adminId = a.getId();
        cookie = "clama_access=" + tokens.emitirPar(a).access() + "; csrftoken=" + csrf;
        Usuario c = new Usuario();
        c.setEmail("comum-edicao@exemplo.com");
        c.setPassword("x");
        c.setActive(true);
        cookieComum = "clama_access=" + tokens.emitirPar(usuarios.saveAndFlush(c)).access() + "; csrftoken=" + csrf;
        Pedido p = new Pedido();
        p.setPlano(planos.findById(UUID.fromString(ApiDePedidos.P1)).orElseThrow());
        p.setValorCentavos(2000);
        p.setNome("Maria Silva");
        p.setEmail("maria@exemplo.com");
        p.setTelefone("11999998888");
        p.setIdade(30);
        p.setSexo("feminino");
        p.setPedidoOracao("Peço oração.");
        p.setProviderPaymentId("pay_1");
        id = pedidos.saveAndFlush(p).getId();
    }

    URI uri(String caminho) {
        return URI.create("http://localhost:" + porta + caminho);
    }

    HttpResponse<String> patch(UUID alvo, String corpo, String ck, boolean comCsrf) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/admin/pedidos/" + alvo + "/"))
                .header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(corpo));
        if (ck != null) {
            b.header("Cookie", ck);
        }
        if (comCsrf) {
            b.header("X-CSRFToken", csrf);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String corpo) throws Exception {
        HttpResponse<String> r = patch(id, corpo, cookie, true);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    String auditoria() {
        return jdbc.queryForObject("select change_message from django_admin_log where object_id = ?", String.class,
                id.toString());
    }

    int alertas() {
        return jdbc.queryForObject("select count(*) from scheduled_tasks where task_instance = ?", Integer.class,
                "alerta-admin/" + id);
    }

    @Test
    void mudaSoOsCamposEnviadosEDevolveODetalhe() throws Exception {
        JsonNode d = ok("""
                {"nome": " Maria Souza ", "idade": null, "sexo": "", "plano": "%s", "valor_centavos": 0,
                 "canal_entrega": "whatsapp", "status": "enviada", "oracao_gerada": "Amém."}""".formatted(P2));
        assertThat(d.get("nome").asString()).isEqualTo("Maria Souza");
        assertThat(d.get("email").asString()).isEqualTo("maria@exemplo.com");
        assertThat(d.get("telefone").asString()).isEqualTo("11999998888");
        assertThat(d.get("idade").isNull()).isTrue();
        assertThat(d.get("sexo").asString()).isEmpty();
        assertThat(d.get("pedido_oracao").asString()).isEqualTo("Peço oração.");
        assertThat(d.get("oracao_gerada").asString()).isEqualTo("Amém.");
        assertThat(d.get("plano").get("id").asString()).isEqualTo(P2);
        assertThat(d.get("valor_centavos").asInt()).isZero();
        assertThat(d.get("canal_entrega").asString()).isEqualTo("whatsapp");
        assertThat(d.get("status").asString()).isEqualTo("enviada");
        assertThat(d.get("provider_payment_id").asString()).isEqualTo("pay_1");
        // so o que mudou, na ordem do formulario, com os verbose_name do model
        assertThat(json.readTree(auditoria()).get(0).get("changed").get("fields").valueStream()
                .map(JsonNode::asString).toList()).containsExactly("Nome", "Idade", "Sexo", "Oração gerada", "Plano",
                        "Valor (centavos)", "Canal de entrega", "Status");
        assertThat(jdbc.queryForObject("select action_flag || ':' || object_repr || ':' || user_id from django_admin_log",
                String.class)).isEqualTo("2:" + id + ":" + adminId);
        assertThat(alertas()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class)).isZero();
    }

    @Test
    void camposSomenteLeituraEDesconhecidosSaoIgnorados() throws Exception {
        ok("""
                {"provider_payment_id": "x", "provider_checkout_url": "y", "id": "%s", "created_at": "2020-01-01T00:00:00Z",
                 "eh_gratuito": true, "cpf_cnpj": "1"}""".formatted(UUID.randomUUID()));
        assertThat(auditoria()).isEqualTo("[]");
        assertThat(jdbc.queryForObject("select provider_payment_id from orders_pedido where id = ?", String.class, id))
                .isEqualTo("pay_1");
    }

    @Test
    void semMudancaGravaAuditoriaVazia() throws Exception {
        ok("{\"nome\": \"Maria Silva\", \"status\": \"aguardando_pagamento\", \"idade\": 30}");
        assertThat(auditoria()).isEqualTo("[]");
        assertThat(jdbc.queryForObject("select action_flag from django_admin_log", Integer.class)).isEqualTo(2);
    }

    @Test
    void statusErroEnfileiraUmAlertaEODedupDeUmaHora() throws Exception {
        ok("{\"status\": \"erro\"}");
        assertThat(alertas()).isEqualTo(1);
        ok("{\"status\": \"erro\"}"); // sem mudanca, mas o Django dispara em qualquer save: o dedup segura
        assertThat(alertas()).isEqualTo(1);
        assertThat(pedidos.findById(id).orElseThrow().getStatus()).isEqualTo(StatusPedido.ERRO);
    }

    @Test
    void statusErroSemMudancaAlertaNoPrimeiroSave() throws Exception {
        jdbc.update("update orders_pedido set status = 'erro' where id = ?", id);
        ok("{\"nome\": \"Maria Silva\"}");
        assertThat(alertas()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"nome\": \"\"}|nome|Este campo pode não estar em branco.",
            "{\"nome\": null}|nome|Este campo pode não ser nulo.",
            "{\"email\": \"sem-arroba\"}|email|Informe um endereço de email válido.",
            "{\"idade\": -1}|idade|Certifque-se de que este valor seja maior ou igual a 0.",
            "{\"idade\": \"x\"}|idade|Um número inteiro válido é exigido.",
            "{\"valor_centavos\": -5}|valor_centavos|Certifque-se de que este valor seja maior ou igual a 0.",
            "{\"sexo\": \"outro\"}|sexo|\"outro\" não é uma escolha válida.",
            "{\"canal_entrega\": \"sms\"}|canal_entrega|\"sms\" não é uma escolha válida.",
            "{\"status\": \"inventado\"}|status|\"inventado\" não é uma escolha válida.",
            "{\"plano\": \"55555555-5555-4555-8555-0000000000ff\"}|plano|Pk inválido \"55555555-5555-4555-8555-0000000000ff\" - objeto não existe.",
            "{\"plano\": \"abc\"}|plano|O valor “abc” não é um UUID válido"})
    void valorInvalidoDa400ESemEfeito(String corpo, String campo, String mensagem) throws Exception {
        HttpResponse<String> r = patch(id, corpo, cookie, true);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get(campo).get(0).asString()).isEqualTo(mensagem);
        assertThat(jdbc.queryForObject("select count(*) from django_admin_log", Integer.class)).isZero();
    }

    @Test
    void corpoQueNaoEObjetoDa400() throws Exception {
        HttpResponse<String> r = patch(id, "[1]", cookie, true);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get("non_field_errors")).isNotNull();
    }

    @Test
    void pedidoInexistenteDa404Pastoral() throws Exception {
        HttpResponse<String> r = patch(UUID.randomUUID(), "{}", cookie, true);
        assertThat(r.statusCode()).isEqualTo(404);
        JsonNode erro = json.readTree(r.body()).get("error");
        assertThat(erro.get("code").asString()).isEqualTo("not_found");
        assertThat(erro.get("pastoral_message").asString()).isNotBlank();
    }

    @Test
    void acessoNegado() throws Exception {
        assertThat(patch(id, "{}", null, false).statusCode()).isEqualTo(401);
        assertThat(patch(id, "{}", cookieComum, true).statusCode()).isEqualTo(403);
        assertThat(patch(id, "{\"nome\": \"X Y\"}", cookie, false).statusCode()).isEqualTo(403);
        assertThat(pedidos.findById(id).orElseThrow().getNome()).isEqualTo("Maria Silva");
    }
}
