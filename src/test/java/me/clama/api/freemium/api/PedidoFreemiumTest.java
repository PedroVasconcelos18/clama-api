package me.clama.api.freemium.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * POST /api/freemium/pedidos/ alem do replay do Django (ContratoFreemiumTest): limiar e janela de IP vindos das
 * propriedades (aqui 2 e 2 h, diferentes dos defaults 3 e 1), falha da fila e formato do token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"FREEMIUM_IP_BLACKLIST_THRESHOLD=2", "FREEMIUM_IP_BLACKLIST_WINDOW_HOURS=2"})
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class PedidoFreemiumTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00Z");
    static final String LIMPAR = "TRUNCATE scheduled_tasks, freemium_freemiumconfirmationtoken,"
            + " freemium_freemiumblacklist, orders_pedido, users_user, plans_plan CASCADE";
    static final ObjectMapper JSON = new ObjectMapper();

    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RelogioDeTeste.Fixavel relogio;
    @Autowired RateLimiter limiter;
    @Autowired HashIdentificador hash;
    @Autowired UsuarioRepository usuarios;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @MockitoSpyBean Fila fila;

    final HttpClient http = HttpClient.newHttpClient();
    UUID plano = UUID.fromString("55555555-5555-4555-8555-000000000005");

    @BeforeEach
    void preparar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute(LIMPAR);
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem,
                    ativo, visivel) values (now(), now(), ?, 'Gratuito', 0, 'd', 'simples_gratuita', 9, true, false)""",
                plano);
        relogio.fixar(AGORA);
    }

    @AfterEach
    void soltar() {
        jdbc.execute(LIMPAR);
        relogio.fixar(null);
    }

    ObjectNode corpo() {
        return JSON.createObjectNode().put("nome", "Maria Silva").put("email", "maria@exemplo.com")
                .put("telefone", "+5511999998888").put("cpf_cnpj", "529.982.247-25").put("idade", 35)
                .put("sexo", "feminino").put("pedido_oracao", "Peço oração.").put("consent_aceito", true)
                .put("turnstile_token", "tok").put("device_hash", "fp-abc123");
    }

    HttpResponse<String> enviar(JsonNode corpo, String ip) throws Exception {
        limiter.limpar(); // 5/h por XFF: cada teste manda mais que isso de proposito so quando quer
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/freemium/pedidos/"))
                .header("Content-Type", "application/json").header("X-Forwarded-For", ip + ", 10.0.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(corpo.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String code(HttpResponse<String> r) {
        return JSON.readTree(r.body()).get("error").get("code").asString();
    }

    void blacklist(String cpfHash, String emailHash, String ipHash, Instant criado) {
        Timestamp t = Timestamp.from(criado);
        jdbc.update("""
                insert into freemium_freemiumblacklist (id, created_at, updated_at, cpf_hash, email_hash, telefone_hash,
                    device_hash, ip_hash) values (?, ?, ?, ?, ?, null, null, ?)""", UUID.randomUUID(), t, t, cpfHash,
                emailHash, ipHash);
    }

    int contar(String tabela) {
        return jdbc.queryForObject("select count(*) from " + tabela, Integer.class);
    }

    @Test
    void aliasGmailCasaGates() throws Exception {
        // a blacklist guardou o hash do e-mail canonico; o alias com pontos, "+" e googlemail cai nele
        blacklist("c".repeat(64), hash.hashEmail("mariasilva@gmail.com"), null, AGORA.minusSeconds(86400));
        var r = enviar(corpo().put("email", "Maria.Silva+oracao@GoogleMail.com"), "198.51.100.1");
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("freemium_blacklist_hit");

        // a conta com o e-mail literal em outra grafia casa no gate de conta, antes da blacklist, com o redirect
        Usuario u = new Usuario();
        u.setEmail("maria.silva@gmail.com");
        u.setPassword("!");
        usuarios.saveAndFlush(u);
        r = enviar(corpo().put("email", "MariaSilva+x@googlemail.com"), "198.51.100.2");
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(r.body()).get("error").get("redirect").asString()).isEqualTo("/login");
        assertThat(code(r)).isEqualTo("user_ja_possui_conta");
        assertThat(contar("orders_pedido")).isZero();
    }

    @Test
    void pendenteBloqueiaPorCpfETelefone() throws Exception {
        tx.executeWithoutResult(s -> {
            Pedido p = new Pedido();
            p.setPlano(planos.getReferenceById(plano));
            p.setNome("Maria Silva");
            p.setEmail("pendente@exemplo.com");
            p.setTelefone("+5511911112222");
            p.setCpfCnpj("52998224725");
            p.setEhGratuito(true);
            p.setStatus(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
            pedidos.save(p);
        });
        // so o CPF igual (com mascara)
        var r = enviar(corpo().put("email", "a@exemplo.com").put("telefone", "+5511977776666"), "198.51.100.1");
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("pedido_em_andamento");
        // so o telefone igual
        r = enviar(corpo().put("email", "b@exemplo.com").put("telefone", "+5511911112222")
                .put("cpf_cnpj", "11.222.333/0001-81"), "198.51.100.2");
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("pedido_em_andamento");
        // nada igual: passa
        r = enviar(corpo().put("email", "c@exemplo.com").put("telefone", "+5511977776666")
                .put("cpf_cnpj", "11.222.333/0001-81"), "198.51.100.3");
        assertThat(r.statusCode()).isEqualTo(201);
    }

    @Test
    void ipNoLimiarBloqueia() throws Exception {
        String ip = "203.0.113.9";
        // limiar 2 e janela 2 h (propriedades do teste): 1 dentro + 1 fora da janela nao bloqueia
        blacklist("1".repeat(64), "1".repeat(64), hash.hashIp(ip), AGORA.minusSeconds(90 * 60));
        blacklist("2".repeat(64), "2".repeat(64), hash.hashIp(ip), AGORA.minusSeconds(2 * 3600 + 1));
        var r = enviar(corpo(), ip);
        assertThat(r.statusCode()).isEqualTo(201);

        jdbc.execute("TRUNCATE orders_pedido, freemium_freemiumconfirmationtoken, scheduled_tasks CASCADE");
        // a 2a dentro da janela (no limite exato: created_at >= agora - 2 h) atinge o limiar
        blacklist("3".repeat(64), "3".repeat(64), hash.hashIp(ip), AGORA.minusSeconds(2 * 3600));
        r = enviar(corpo(), ip);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("freemium_blacklist_hit");
        // outro IP passa
        r = enviar(corpo(), "198.51.100.7");
        assertThat(r.statusCode()).isEqualTo(201);
    }

    @Test
    void falhaAoEnfileirarDa500SemPedido() throws Exception {
        doThrow(new IllegalStateException("fila fora")).when(fila)
                .enfileirar(any(TipoDeJob.class), anyString(), any(UUID.class));
        var r = enviar(corpo(), "198.51.100.1");
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(contar("orders_pedido")).isZero();
        assertThat(contar("freemium_freemiumconfirmationtoken")).isZero();
        assertThat(contar("scheduled_tasks")).isZero();
    }

    @Test
    void tokenTem64CaracteresUrlSafe() throws Exception {
        var r = enviar(corpo(), "203.0.113.9");
        assertThat(r.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(r.body());
        assertThat(r.body()).startsWith("{\"pedido_id\":");
        assertThat(body.get("status").asString()).isEqualTo("aguardando_confirmacao_email");
        UUID id = UUID.fromString(body.get("pedido_id").asString());

        Map<String, Object> t = jdbc.queryForMap("""
                select token, expires_at, host(ip_origem) ip, device_hash, used_at from freemium_freemiumconfirmationtoken
                where pedido_id = ?""", id);
        assertThat((String) t.get("token")).matches("[A-Za-z0-9_-]{64}");
        assertThat(((Timestamp) t.get("expires_at")).toInstant()).isEqualTo(AGORA.plusSeconds(86400));
        assertThat(t.get("ip")).isEqualTo("203.0.113.9");
        assertThat(t.get("device_hash")).isEqualTo("fp-abc123");
        assertThat(t.get("used_at")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = ? and task_instance = ?",
                Integer.class, "confirmacao-freemium", "confirmacao-freemium/" + id)).isEqualTo(1);
    }

    /** Cookie de acesso invalido numa rota aberta e 401 (como no Django). */
    @Test
    void cookieInvalidoDa401() throws Exception {
        limiter.limpar();
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/freemium/pedidos/"))
                .header("Content-Type", "application/json").header("Cookie", "clama_access=lixo")
                .POST(HttpRequest.BodyPublishers.ofString(corpo().toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(code(r)).isEqualTo("not_authenticated");
        assertThat(contar("orders_pedido")).isZero();
    }

    /** Django device_hash_vazio_nao_dispara_check: linha legada com device_hash '' nao bloqueia device vazio/ausente. */
    @Test
    void deviceHashVazioNaoCasaComLinhaLegadaVazia() throws Exception {
        Timestamp t = Timestamp.from(AGORA);
        jdbc.update("""
                insert into freemium_freemiumblacklist (id, created_at, updated_at, cpf_hash, email_hash, telefone_hash,
                    device_hash, ip_hash) values (?, ?, ?, ?, ?, null, '', null)""", UUID.randomUUID(), t, t,
                "9".repeat(64), "9".repeat(64));
        assertThat(enviar(corpo().put("device_hash", ""), "198.51.100.31").statusCode()).isEqualTo(201);
        jdbc.execute("TRUNCATE orders_pedido, freemium_freemiumconfirmationtoken, scheduled_tasks CASCADE");
        var semCampo = corpo();
        semCampo.remove("device_hash");
        assertThat(enviar(semCampo, "198.51.100.32").statusCode()).isEqualTo(201);
    }
}
