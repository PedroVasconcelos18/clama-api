package me.clama.api.freemium.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.cripto.HashIdentificador;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * /api/admin/freemium/bloqueios/ (lista, buscar em claro, desbloquear com auditoria) e /tokens/ (so leitura): mapa 7
 * §3, D11 e D12.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "TURNSTILE_SECRET_KEY="})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class BloqueiosAdminTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "/api/admin/freemium/";

    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired HashIdentificador hash;

    final HttpClient http = HttpClient.newHttpClient();
    long adminId;
    String cookie;
    String csrf;
    String cookieComum;

    @BeforeEach
    void preparar() throws Exception {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE users_user, plans_plan, instituicoes_instituicao, freemium_freemiumconfirmationtoken, "
                + "orders_pedido, freemium_freemiumblacklist, django_admin_log RESTART IDENTITY CASCADE");
        Usuario a = new Usuario();
        a.setEmail("admin-bloqueios@exemplo.com");
        a.setPassword("x");
        a.setClamaAdmin(true);
        a = usuarios.saveAndFlush(a);
        adminId = a.getId();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/csrf/")).build(),
                HttpResponse.BodyHandlers.ofString());
        csrf = JSON.readTree(r.body()).get("csrf_token").asString();
        cookie = "clama_access=" + tokens.emitirPar(a).access() + "; csrftoken=" + csrf;
        Usuario c = new Usuario();
        c.setEmail("comum-bloqueios@exemplo.com");
        c.setPassword("x");
        cookieComum = "clama_access=" + tokens.emitirPar(usuarios.saveAndFlush(c)).access() + "; csrftoken=" + csrf;
    }

    URI uri(String caminho) {
        return URI.create("http://localhost:" + porta + caminho);
    }

    /** Linha da blacklist com hashes de verdade; minutosAtras ordena a lista. */
    UUID bloqueio(String email, String cpf, String telefone, int minutosAtras) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into freemium_freemiumblacklist (created_at, updated_at, id, cpf_hash, email_hash, telefone_hash,
                    device_hash, ip_hash)
                values (now() - ? * interval '1 minute', now(), ?, ?, ?, ?, 'fp-device', '')""", minutosAtras, id,
                hash.hashCpfCnpj(cpf), hash.hashEmail(email), hash.hashTelefone(telefone));
        return id;
    }

    UUID pedido() {
        UUID plano = UUID.randomUUID();
        UUID instituicao = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem,
                    ativo, visivel) values (now(), now(), ?, 'Pedido de Oração', 2000, 'd', 'simples', 1, true, true)""",
                plano);
        jdbc.update("""
                insert into instituicoes_instituicao (created_at, updated_at, id, nome, logo, ativo, ordem)
                values (now(), now(), ?, 'Lar Esperança', '', true, 1)""", instituicao);
        jdbc.update("""
                insert into orders_pedido (created_at, updated_at, id, nome, email, telefone, cpf_cnpj, idade, sexo,
                    pedido_oracao, oracao_gerada, valor_centavos, canal_entrega, status, plano_id, instituicao_id,
                    consent_aceito, consent_versao, last_error, retry_count, whatsapp_message_id, eh_gratuito,
                    device_hash, provider_payment_id)
                values (now(), now(), ?, 'Maria Silva', 'maria@exemplo.com', '', '52998224725', 35, 'feminino',
                    'Peço oração.', '', 0, 'email', 'aguardando_confirmacao_email', ?, ?, true, '1', '', 0, '', true,
                    '', '')""", id, plano, instituicao);
        return id;
    }

    UUID token(UUID pedido, String valor, int minutosAtras) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into freemium_freemiumconfirmationtoken (created_at, updated_at, id, token, expires_at, used_at,
                    ip_origem, device_hash, pedido_id)
                values (now() - ? * interval '1 minute', now(), ?, ?, now() + interval '1 day', null,
                    cast('203.0.113.7' as inet), 'fp-device-token', ?)""", minutosAtras, id, valor, pedido);
        return id;
    }

    HttpResponse<String> chamar(String metodo, String caminho, String corpo, String ck, boolean comCsrf)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(BASE + caminho)).header("Content-Type", "application/json")
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(corpo));
        if (ck != null) {
            b.header("Cookie", ck);
        }
        if (comCsrf) {
            b.header("X-CSRFToken", csrf);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String metodo, String caminho, String corpo) throws Exception {
        HttpResponse<String> r = chamar(metodo, caminho, corpo, cookie, true);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    String codigo(HttpResponse<String> r) {
        return JSON.readTree(r.body()).get("error").get("code").asString();
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void buscarPorEmailEmClaroAchaPeloHash() throws Exception {
        UUID alvo = bloqueio("Maria.Silva+x@gmail.com", "52998224725", "+5511999990001", 1);
        bloqueio("outro@exemplo.com", "11144477735", "+5511999990002", 2);

        // a mesma normalizacao do gate: caixa, ponto e +tag do gmail
        JsonNode r = ok("POST", "bloqueios/buscar/", "{\"email\": \" MARIASILVA@gmail.com \"}");
        assertThat(r.size()).isEqualTo(1);
        assertThat(r.get(0).get("id").asString()).isEqualTo(alvo.toString());
        // CPF formatado e telefone com mascara casam pelos digitos; casa em qualquer campo (OR)
        assertThat(ok("POST", "bloqueios/buscar/", "{\"cpf_cnpj\": \"529.982.247-25\"}").size()).isEqualTo(1);
        assertThat(ok("POST", "bloqueios/buscar/", "{\"telefone\": \"+55 (11) 99999-0002\"}").get(0).get("id")
                .asString()).isNotEqualTo(alvo.toString());
        assertThat(ok("POST", "bloqueios/buscar/", "{\"email\": \"mariasilva@gmail.com\", \"cpf_cnpj\": "
                + "\"111.444.777-35\"}").size()).isEqualTo(2);
        assertThat(ok("POST", "bloqueios/buscar/", "{\"email\": \"ninguem@exemplo.com\"}").size()).isZero();

        // nada informado, ou so espaco / letras onde cabem digitos: 400 DRF, e nunca casa o hash do vazio
        jdbc.update("update freemium_freemiumblacklist set telefone_hash = ?", hash.hashTelefone(""));
        for (String corpo : List.of("{}", "{\"email\": \"  \", \"telefone\": \"\"}", "{\"telefone\": \"abc\"}",
                "{\"email\": null}")) {
            HttpResponse<String> v = chamar("POST", "bloqueios/buscar/", corpo, cookie, true);
            assertThat(v.statusCode()).as(corpo).isEqualTo(400);
            assertThat(JSON.readTree(v.body()).get("non_field_errors").get(0).asString())
                    .isEqualTo("Informe e-mail, CPF/CNPJ ou telefone.");
        }
    }

    @Test
    void hashesTruncadosNaLista() throws Exception {
        UUID novo = bloqueio("a@exemplo.com", "52998224725", "+5511999990001", 1);
        UUID velho = bloqueio("b@exemplo.com", "11144477735", "+5511999990002", 5);
        jdbc.update("update freemium_freemiumblacklist set telefone_hash = null, device_hash = '' where id = ?", velho);

        JsonNode l = ok("GET", "bloqueios/?limit=1", null);
        assertThat(l.propertyNames()).containsExactly("count", "next", "previous", "results");
        assertThat(l.get("count").asInt()).isEqualTo(2);
        assertThat(l.get("results").size()).isEqualTo(1);
        JsonNode item = l.get("results").get(0);
        assertThat(item.propertyNames()).containsExactly("id", "created_at", "cpf_hash_curto", "email_hash_curto",
                "tem_telefone", "tem_device", "tem_ip");
        assertThat(item.get("id").asString()).isEqualTo(novo.toString());
        assertThat(item.get("cpf_hash_curto").asString()).isEqualTo(hash.hashCpfCnpj("52998224725").substring(0, 12) + "…");
        assertThat(item.get("email_hash_curto").asString()).isEqualTo(hash.hashEmail("a@exemplo.com").substring(0, 12)
                + "…");
        assertThat(item.get("tem_telefone").asBoolean()).isTrue();
        assertThat(item.get("tem_device").asBoolean()).isTrue();
        assertThat(item.get("tem_ip").asBoolean()).as("ip_hash vazio").isFalse();

        JsonNode segundo = ok("GET", "bloqueios/?limit=1&offset=1", null).get("results").get(0);
        assertThat(segundo.get("id").asString()).isEqualTo(velho.toString());
        assertThat(segundo.get("tem_telefone").asBoolean()).isFalse();
        assertThat(segundo.get("tem_device").asBoolean()).isFalse();

        String todo = ok("GET", "bloqueios/", null).toString() + ok("POST", "bloqueios/buscar/",
                "{\"email\": \"a@exemplo.com\"}");
        assertThat(todo).doesNotContain(hash.hashCpfCnpj("52998224725")).doesNotContain(hash.hashEmail("a@exemplo.com"))
                .doesNotContain("fp-device");
    }

    @Test
    void desbloqueioGravaAuditoria() throws Exception {
        UUID id = bloqueio("a@exemplo.com", "52998224725", "+5511999990001", 1);
        UUID fica = bloqueio("b@exemplo.com", "11144477735", "+5511999990002", 2);
        jdbc.update("insert into django_content_type (app_label, model) values ('freemium', 'freemiumblacklist')");

        HttpResponse<String> r = chamar("DELETE", "bloqueios/" + id + "/", null, cookie, true);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(contar("select count(*) from freemium_freemiumblacklist where id = ?", id)).isZero();
        assertThat(contar("select count(*) from freemium_freemiumblacklist where id = ?", fica)).isEqualTo(1);

        var log = jdbc.queryForMap("""
                select l.object_id, l.object_repr, l.action_flag, l.change_message, l.user_id, c.model
                  from django_admin_log l join django_content_type c on c.id = l.content_type_id""");
        assertThat(log.get("object_id")).isEqualTo(id.toString());
        assertThat(log.get("object_repr")).isEqualTo(id.toString());
        assertThat(((Number) log.get("action_flag")).intValue()).isEqualTo(3);
        assertThat(log.get("change_message")).isEqualTo("");
        assertThat(((Number) log.get("user_id")).longValue()).isEqualTo(adminId);
        assertThat(log.get("model")).isEqualTo("freemiumblacklist");
        assertThat(contar("select count(*) from django_admin_log")).isEqualTo(1);

        // desbloqueio e irreversivel e o segundo DELETE nao acha a linha
        HttpResponse<String> de_novo = chamar("DELETE", "bloqueios/" + id + "/", null, cookie, true);
        assertThat(de_novo.statusCode()).isEqualTo(404);
        assertThat(contar("select count(*) from django_admin_log")).isEqualTo(1);
    }

    @Test
    void deleteDeIdDesconhecido404Pastoral() throws Exception {
        bloqueio("a@exemplo.com", "52998224725", "+5511999990001", 1);
        HttpResponse<String> r = chamar("DELETE", "bloqueios/" + UUID.randomUUID() + "/", null, cookie, true);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(codigo(r)).isEqualTo("not_found");
        assertThat(JSON.readTree(r.body()).get("error").get("pastoral_message").asString()).isNotBlank();
        assertThat(contar("select count(*) from freemium_freemiumblacklist")).isEqualTo(1);
        assertThat(contar("select count(*) from django_admin_log")).isZero();
    }

    @Test
    void tokenSemIpOrigem() throws Exception {
        UUID p1 = pedido();
        UUID novo = token(p1, "abcdefgh1234567890SEGREDO", 1);
        UUID usado = token(pedido(), "zzzzzzzz9999", 5);
        jdbc.update("update freemium_freemiumconfirmationtoken set used_at = now() where id = ?", usado);

        JsonNode l = ok("GET", "tokens/", null);
        assertThat(l.propertyNames()).containsExactly("count", "next", "previous", "results");
        assertThat(l.get("count").asInt()).isEqualTo(2);
        JsonNode item = l.get("results").get(0);
        assertThat(item.propertyNames()).containsExactly("id", "token_curto", "pedido_id", "created_at", "expires_at",
                "used_at");
        assertThat(item.get("id").asString()).isEqualTo(novo.toString());
        assertThat(item.get("token_curto").asString()).isEqualTo("abcdefgh…");
        assertThat(item.get("pedido_id").asString()).isEqualTo(p1.toString());
        assertThat(item.get("used_at").isNull()).isTrue();
        assertThat(l.get("results").get(1).get("used_at").isNull()).isFalse();
        assertThat(l.toString()).doesNotContain("SEGREDO").doesNotContain("203.0.113.7").doesNotContain("ip_origem")
                .doesNotContain("fp-device-token").doesNotContain("device_hash");

        JsonNode filtrado = ok("GET", "tokens/?pedido=" + p1 + "&pedido=" + p1, null);
        assertThat(filtrado.get("count").asInt()).isEqualTo(1);
        assertThat(ok("GET", "tokens/?pedido=" + UUID.randomUUID(), null).get("count").asInt()).isZero();
        HttpResponse<String> mal = chamar("GET", "tokens/?pedido=xyz", null, cookie, true);
        assertThat(mal.statusCode()).isEqualTo(400);
        assertThat(mal.body()).isEqualTo("{\"detail\":\"Parâmetro pedido inválido.\"}");

        // sem delete (D12)
        assertThat(chamar("DELETE", "tokens/" + novo + "/", null, cookie, true).statusCode()).isEqualTo(404);
        assertThat(contar("select count(*) from freemium_freemiumconfirmationtoken")).isEqualTo(2);
    }

    @Test
    void naoAdmin403() throws Exception {
        UUID id = bloqueio("a@exemplo.com", "52998224725", "+5511999990001", 1);
        for (HttpResponse<String> r : List.of(chamar("GET", "bloqueios/", null, cookieComum, true),
                chamar("POST", "bloqueios/buscar/", "{\"email\": \"a@exemplo.com\"}", cookieComum, true),
                chamar("DELETE", "bloqueios/" + id + "/", null, cookieComum, true),
                chamar("GET", "tokens/", null, cookieComum, true))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(r.body()).isEqualTo("{\"detail\":\"Esse espaço é só para admins do Clama.\"}");
        }
        assertThat(contar("select count(*) from freemium_freemiumblacklist")).isEqualTo(1);
    }

    @Test
    void anonimo401() throws Exception {
        UUID id = bloqueio("a@exemplo.com", "52998224725", "+5511999990001", 1);
        for (HttpResponse<String> r : List.of(chamar("GET", "bloqueios/", null, null, false),
                chamar("POST", "bloqueios/buscar/", "{\"email\": \"a@exemplo.com\"}", null, false),
                chamar("DELETE", "bloqueios/" + id + "/", null, null, false),
                chamar("GET", "tokens/", null, null, false))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
            assertThat(codigo(r)).isEqualTo("not_authenticated");
        }
        assertThat(contar("select count(*) from freemium_freemiumblacklist")).isEqualTo(1);
    }

    @Test
    void escritasSemCsrf403() throws Exception {
        UUID id = bloqueio("a@exemplo.com", "52998224725", "+5511999990001", 1);
        for (HttpResponse<String> r : List.of(
                chamar("POST", "bloqueios/buscar/", "{\"email\": \"a@exemplo.com\"}", cookie, false),
                chamar("DELETE", "bloqueios/" + id + "/", null, cookie, false))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(codigo(r)).isEqualTo("csrf_invalido");
        }
        assertThat(contar("select count(*) from freemium_freemiumblacklist")).isEqualTo(1);
        assertThat(contar("select count(*) from django_admin_log")).isZero();
    }
}
