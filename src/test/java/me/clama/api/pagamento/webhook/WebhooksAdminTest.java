package me.clama.api.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import me.clama.api.oracao.SenhasTemporarias;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * /api/admin/webhooks/: lista sem payload, detalhe com payload, reprocessar (D9: so RECEBIDO/ERRO; WP enfileira, MP
 * passa pelo ProcessamentoDePagamento, ASAAS/ZAPI nunca), auditoria, 401/403/CSRF e a corrida com a reentrega do MP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "MERCADOPAGO_ACCESS_TOKEN=test", "MERCADOPAGO_WEBHOOK_SECRET=" + WebhooksAdminTest.SEGREDO,
        "TURNSTILE_SECRET_KEY="})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class WebhooksAdminTest {
    static final String SEGREDO = "segredo-webhooks-admin";
    static final String PAGAMENTO = "1325814499";
    static final ObjectMapper JSON = new ObjectMapper();
    static volatile UUID pedidoDoPagamento;
    /** Status de erro das proximas respostas do GET (uma por chamada); vazia = aprovado. */
    static final java.util.Queue<Integer> FALHAS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    static final HttpServer MP;

    static {
        try {
            MP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MP.setExecutor(Executors.newCachedThreadPool());
            MP.createContext("/v1/payments/" + PAGAMENTO, ex -> {
                try {
                    Thread.sleep(300); // a reentrega e o reprocesso chegam ao lock do pedido juntos
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Integer falha = FALHAS.poll();
                byte[] corpo = (falha != null
                        ? "{\"message\": \"erro " + falha + "\", \"status\": " + falha + ", \"cause\": []}"
                        : "{\"id\": " + PAGAMENTO + ", \"status\": \"approved\", \"external_reference\": \""
                                + pedidoDoPagamento + "\"}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(falha != null ? falha : 200, corpo.length);
                ex.getResponseBody().write(corpo);
                ex.close();
            });
            MP.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void mercadoPago(DynamicPropertyRegistry r) {
        r.add("clama.mercadopago.url", () -> "http://127.0.0.1:" + MP.getAddress().getPort());
    }

    @AfterAll
    static void parar() {
        MP.stop(0);
    }

    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired SenhasTemporarias senhas;
    @Autowired ProcessamentoDePagamento processamento;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    final HttpClient http = HttpClient.newHttpClient();
    long adminId;
    String cookie;
    String csrf;
    String cookieComum;

    @BeforeEach
    void preparar() throws Exception {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE users_user, plans_plan, instituicoes_instituicao, orders_pedido, instituicoes_repasse, "
                + "payments_webhookevento, scheduled_tasks, django_admin_log RESTART IDENTITY CASCADE");
        senhas.limpar();
        FALHAS.clear();
        Usuario a = new Usuario();
        a.setEmail("admin-webhooks@exemplo.com");
        a.setPassword("x");
        a.setClamaAdmin(true);
        a = usuarios.saveAndFlush(a);
        adminId = a.getId();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/csrf/")).build(),
                HttpResponse.BodyHandlers.ofString());
        csrf = JSON.readTree(r.body()).get("csrf_token").asString();
        cookie = "clama_access=" + tokens.emitirPar(a).access() + "; csrftoken=" + csrf;
        Usuario c = new Usuario();
        c.setEmail("comum-webhooks@exemplo.com");
        c.setPassword("x");
        cookieComum = "clama_access=" + tokens.emitirPar(usuarios.saveAndFlush(c)).access() + "; csrftoken=" + csrf;

        UUID plano = UUID.randomUUID();
        UUID instituicao = UUID.randomUUID();
        pedidoDoPagamento = UUID.randomUUID();
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
                    'Peço oração.', '', 3001, 'email', 'aguardando_pagamento', ?, ?, true, '1', '', 0, '', false, '',
                    ?)""", pedidoDoPagamento, plano, instituicao, PAGAMENTO);
    }

    URI uri(String caminho) {
        return URI.create("http://localhost:" + porta + caminho);
    }

    /** Evento gravado direto, com created_at = agora - minutos. */
    UUID evento(String provider, String externo, String tipo, String status, String payload, int minutosAtras) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into payments_webhookevento (created_at, updated_at, id, provider, external_event_id, event_type,
                    payload, status, error_message, pedido_id)
                values (now() - ? * interval '1 minute', now(), ?, ?, ?, ?, ?::jsonb, ?, ?, null)""", minutosAtras, id,
                provider, externo, tipo, payload, status, status.equals("ERRO") ? "erro 429" : "");
        return id;
    }

    UUID eventoMp(String status) {
        return evento("MERCADO_PAGO", "777", "payment", status, "{\"id\": 777, \"type\": \"payment\", "
                + "\"action\": \"payment.updated\", \"data\": {\"id\": \"" + PAGAMENTO + "\"}}", 0);
    }

    HttpResponse<String> get(String caminho, String ck) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/admin/webhooks/" + caminho));
        if (ck != null) {
            b.header("Cookie", ck);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode getOk(String caminho) throws Exception {
        HttpResponse<String> r = get(caminho, cookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    HttpResponse<String> reprocessar(UUID id, String ck, boolean comCsrf) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/admin/webhooks/" + id + "/reprocessar/"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (ck != null) {
            b.header("Cookie", ck);
        }
        if (comCsrf) {
            b.header("X-CSRFToken", csrf);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> reprocessar(UUID id) throws Exception {
        return reprocessar(id, cookie, true);
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    String codigo(HttpResponse<String> r) {
        return JSON.readTree(r.body()).get("error").get("code").asString();
    }

    String status(UUID id) {
        return jdbc.queryForObject("select status from payments_webhookevento where id = ?", String.class, id);
    }

    @Test
    void listaSemPayloadMostraPayloadSoNoDetalhe() throws Exception {
        UUID velho = evento("ZAPI", "z-1", "message", "PROCESSADO",
                "{\"phone\": \"5511999990000\", \"text\": \"segredo do pagador\"}", 10);
        UUID novo = eventoMp("ERRO");

        JsonNode l = getOk("");
        assertThat(l.propertyNames()).containsExactly("count", "next", "previous", "results");
        assertThat(l.get("count").asInt()).isEqualTo(2);
        List<String> ids = new ArrayList<>();
        l.get("results").forEach(n -> ids.add(n.get("id").asString()));
        assertThat(ids).containsExactly(novo.toString(), velho.toString());
        JsonNode item = l.get("results").get(0);
        assertThat(item.propertyNames()).containsExactly("id", "created_at", "updated_at", "provider", "event_type",
                "status", "external_event_id", "pedido_id", "error_message");
        assertThat(item.get("provider").asString()).isEqualTo("MERCADO_PAGO");
        assertThat(item.get("error_message").asString()).isEqualTo("erro 429");
        assertThat(item.get("pedido_id").isNull()).isTrue();
        assertThat(l.toString()).doesNotContain("5511999990000").doesNotContain("segredo do pagador")
                .doesNotContain("payload");

        JsonNode d = getOk(velho + "/");
        assertThat(d.propertyNames()).containsExactly("id", "created_at", "updated_at", "provider", "event_type",
                "status", "external_event_id", "pedido_id", "error_message", "payload");
        assertThat(d.get("payload").get("phone").asString()).isEqualTo("5511999990000");
        assertThat(d.get("payload").get("text").asString()).isEqualTo("segredo do pagador");

        HttpResponse<String> ausente = get(UUID.randomUUID() + "/", cookie);
        assertThat(ausente.statusCode()).isEqualTo(404);
        assertThat(codigo(ausente)).isEqualTo("not_found");
    }

    @Test
    void filtrosBuscaEPaginacao() throws Exception {
        UUID mp = eventoMp("ERRO");
        UUID wp = evento("WORDPRESS", "wp-1", "post_publicado", "RECEBIDO", "{\"wp_post_id\": 1}", 5);
        evento("ASAAS", "pay_100%", "PAYMENT_CONFIRMED", "IGNORADO", "{}", 9);
        jdbc.update("update payments_webhookevento set pedido_id = ? where id = ?", pedidoDoPagamento, wp);

        assertThat(getOk("?provider=WORDPRESS").get("results").get(0).get("id").asString()).isEqualTo(wp.toString());
        assertThat(getOk("?status=IGNORADO&status=ERRO").get("results").get(0).get("id").asString())
                .as("parametro repetido: vale o ultimo").isEqualTo(mp.toString());
        assertThat(getOk("?event_type=payment").get("count").asInt()).isEqualTo(1);
        assertThat(getOk("?provider=NADA").get("count").asInt()).isZero();
        assertThat(getOk("?q=pay_100%25").get("count").asInt()).as("curinga literal").isEqualTo(1);
        assertThat(getOk("?q=pay_1000").get("count").asInt()).isZero();
        assertThat(getOk("?q=" + pedidoDoPagamento.toString().substring(0, 8)).get("results").get(0).get("id")
                .asString()).as("busca pelo id do pedido").isEqualTo(wp.toString());
        JsonNode p = getOk("?limit=1&offset=1");
        assertThat(p.get("count").asInt()).isEqualTo(3);
        assertThat(p.get("results").get(0).get("id").asString()).isEqualTo(wp.toString());
        assertThat(p.get("next").isNull()).isFalse();
        assertThat(p.get("previous").isNull()).isFalse();
    }

    /**
     * MP em ERRO: o reprocesso com o MP ainda fora fica ERRO e responde 502; o seguinte processa uma vez (pago,
     * repasse, conta, jobs) e o evento vira PROCESSADO; reprocessar de novo da 409. Cada tentativa grava auditoria.
     */
    @Test
    void reprocessarMpEmErro() throws Exception {
        UUID id = eventoMp("ERRO");

        FALHAS.add(429); // 5xx o cliente retenta; 429 vai direto a ERRO
        HttpResponse<String> fora = reprocessar(id);
        assertThat(fora.statusCode()).as(fora.body()).isEqualTo(502);
        assertThat(codigo(fora)).isEqualTo("mercadopago_indisponivel");
        assertThat(JSON.readTree(fora.body()).get("error").get("pastoral_message").asString()).contains("erro");
        assertThat(status(id)).isEqualTo("ERRO");
        assertThat(jdbc.queryForObject("select error_message from payments_webhookevento", String.class))
                .isNotBlank().isNotEqualTo("erro 429");
        assertThat(jdbc.queryForObject("select status from orders_pedido", String.class))
                .isEqualTo("aguardando_pagamento");

        HttpResponse<String> ok = reprocessar(id);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(status(id)).isEqualTo("PROCESSADO");
        assertThat(jdbc.queryForObject("select pedido_id from payments_webhookevento", UUID.class))
                .isEqualTo(pedidoDoPagamento);
        assertThat(jdbc.queryForObject("select status from orders_pedido", String.class)).isEqualTo("pago");
        assertThat(contar("select count(*) from instituicoes_repasse")).isEqualTo(1);
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'gerar-oracao'")).isEqualTo(1);
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'boas-vindas'")).isEqualTo(1);

        HttpResponse<String> denovo = reprocessar(id);
        assertThat(denovo.statusCode()).isEqualTo(409);
        assertThat(codigo(denovo)).isEqualTo("webhook_ja_processado");
        assertThat(contar("select count(*) from instituicoes_repasse")).isEqualTo(1);

        List<Map<String, Object>> log = jdbc.queryForList("select user_id, object_id, action_flag, change_message "
                + "from django_admin_log order by id");
        assertThat(log).hasSize(2);
        for (int i = 0; i < log.size(); i++) {
            Map<String, Object> linha = log.get(i);
            assertThat(((Number) linha.get("user_id")).longValue()).isEqualTo(adminId);
            assertThat(linha.get("object_id")).isEqualTo(id.toString());
            assertThat(((Number) linha.get("action_flag")).intValue()).isEqualTo(2);
            // a 1a tentativa foi ERRO->ERRO (sem mudanca); a 2a, ERRO->PROCESSADO
            assertThat(linha.get("change_message")).isEqualTo(i == 0 ? "[]"
                    : "[{\"changed\":{\"fields\":[\"status\"]}}]");
        }
    }

    /** ERRO->ERRO nao mudou o status: o historico do Django nao pode dizer "Changed status". */
    @Test
    void auditoriaVaziaQuandoOStatusNaoMudou() throws Exception {
        UUID id = eventoMp("ERRO");
        FALHAS.add(429);
        assertThat(reprocessar(id).statusCode()).isEqualTo(502);
        assertThat(status(id)).isEqualTo("ERRO");
        assertThat(jdbc.queryForObject("select change_message from django_admin_log", String.class)).isEqualTo("[]");
    }

    @Test
    void eventoSemDataIdViraIgnorado() throws Exception {
        for (String inicial : List.of("RECEBIDO", "ERRO")) {
            UUID id = evento("MERCADO_PAGO", "sem-id-" + inicial, "payment", inicial, "{\"data\": {}}", 0);
            HttpResponse<String> r = reprocessar(id);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(r.body()).isEqualTo("{\"status\":\"ignored\"}");
            assertThat(status(id)).isEqualTo("IGNORADO");
            assertThat(jdbc.queryForObject("select error_message from payments_webhookevento where id = ?",
                    String.class, id)).isEqualTo("data.id ausente no payload");
        }
        assertThat(contar("select count(*) from django_admin_log")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from orders_pedido", String.class))
                .isEqualTo("aguardando_pagamento");
    }

    @Test
    void excecaoInesperadaDa500EDeixaOEventoEmErro() throws Exception {
        UUID id = eventoMp("RECEBIDO");
        jdbc.execute("create function falha_teste() returns trigger as $$ begin raise exception 'boom'; end; $$ "
                + "language plpgsql");
        jdbc.execute("create trigger falha_teste before update on orders_pedido for each row execute function "
                + "falha_teste()");
        try {
            HttpResponse<String> r = reprocessar(id);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(500);
            assertThat(codigo(r)).isEqualTo("reprocesso_falhou");
        } finally {
            jdbc.execute("drop trigger falha_teste on orders_pedido");
            jdbc.execute("drop function falha_teste()");
        }
        assertThat(status(id)).isEqualTo("ERRO");
        assertThat(jdbc.queryForObject("select error_message from payments_webhookevento", String.class))
                .contains("boom");
        assertThat(jdbc.queryForObject("select status from orders_pedido", String.class))
                .isEqualTo("aguardando_pagamento");
        assertThat(contar("select count(*) from instituicoes_repasse")).isZero();
    }

    /** A auditoria e do mesmo commit do trabalho: se o insert do log falha, o pedido nao fica pago. */
    @Test
    void auditoriaQueFalhaDesfazOTrabalho() throws Exception {
        UUID id = eventoMp("ERRO");
        jdbc.execute("create function falha_log() returns trigger as $$ begin raise exception 'sem log'; end; $$ "
                + "language plpgsql");
        jdbc.execute("create trigger falha_log before insert on django_admin_log for each row execute function "
                + "falha_log()");
        try {
            HttpResponse<String> r = reprocessar(id);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(500);
        } finally {
            jdbc.execute("drop trigger falha_log on django_admin_log");
            jdbc.execute("drop function falha_log()");
        }
        assertThat(jdbc.queryForObject("select status from orders_pedido", String.class))
                .isEqualTo("aguardando_pagamento");
        assertThat(contar("select count(*) from instituicoes_repasse")).isZero();
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'gerar-oracao'")).isZero();
        assertThat(status(id)).isEqualTo("ERRO");
    }

    /** O GET ao MP nunca roda dentro de transacao (exactly-once do dinheiro). */
    @Test
    void processarDentroDeTransacaoFalha() {
        UUID id = eventoMp("ERRO");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> processamento.processar(id, "777", PAGAMENTO, () -> {})))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("próprias transações");
        assertThat(status(id)).isEqualTo("ERRO");
    }

    @Test
    void reprocessarProcessado409() throws Exception {
        for (String terminal : List.of("PROCESSADO", "IGNORADO")) {
            UUID id = evento("MERCADO_PAGO", "t-" + terminal, "payment", terminal,
                    "{\"data\": {\"id\": \"" + PAGAMENTO + "\"}}", 0);
            HttpResponse<String> r = reprocessar(id);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
            assertThat(codigo(r)).isEqualTo("webhook_ja_processado");
            assertThat(JSON.readTree(r.body()).get("error").get("pastoral_message").asString())
                    .isEqualTo("Esse evento já foi processado.");
            assertThat(status(id)).isEqualTo(terminal);
        }
        assertThat(contar("select count(*) from django_admin_log")).isZero();
        assertThat(jdbc.queryForObject("select status from orders_pedido", String.class))
                .isEqualTo("aguardando_pagamento");
        assertThat(reprocessar(UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void reprocessarWpEnfileira() throws Exception {
        UUID id = evento("WORDPRESS", "wp-9", "post_publicado", "ERRO", "{\"wp_post_id\": \"x\"}", 0);
        HttpResponse<String> r = reprocessar(id);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        assertThat(r.body()).isEqualTo("{\"status\":\"enfileirado\"}");
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'sincronizar-espelho' "
                + "and task_instance = ?", "sincronizar-espelho/" + id)).isEqualTo(1);

        HttpResponse<String> denovo = reprocessar(id);
        assertThat(denovo.statusCode()).isEqualTo(202);
        assertThat(denovo.body()).isEqualTo("{\"status\":\"ja_na_fila\"}");
        assertThat(contar("select count(*) from scheduled_tasks")).isEqualTo(1);
        assertThat(contar("select count(*) from django_admin_log where action_flag = 2")).isEqualTo(2);
    }

    @Test
    void asaasNaoReprocessa() throws Exception {
        for (String provider : List.of("ASAAS", "ZAPI")) {
            UUID id = evento(provider, "legado-" + provider, "PAYMENT_CONFIRMED", "ERRO", "{}", 0);
            HttpResponse<String> r = reprocessar(id);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
            assertThat(codigo(r)).isEqualTo("webhook_sem_reprocesso");
            assertThat(status(id)).isEqualTo("ERRO");
        }
        assertThat(contar("select count(*) from django_admin_log")).isZero();
        assertThat(contar("select count(*) from scheduled_tasks")).isZero();
    }

    @Test
    void naoAdmin403() throws Exception {
        UUID id = eventoMp("ERRO");
        for (HttpResponse<String> r : List.of(get("", cookieComum), get(id + "/", cookieComum),
                reprocessar(id, cookieComum, true))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(r.body()).isEqualTo("{\"detail\":\"Esse espaço é só para admins do Clama.\"}");
        }
        assertThat(status(id)).isEqualTo("ERRO");
    }

    @Test
    void anonimo401() throws Exception {
        UUID id = eventoMp("ERRO");
        for (HttpResponse<String> r : List.of(get("", null), get(id + "/", null), reprocessar(id, null, false))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
            assertThat(codigo(r)).isEqualTo("not_authenticated");
        }
        assertThat(status(id)).isEqualTo("ERRO");
    }

    @Test
    void reprocessarSemCsrf403() throws Exception {
        UUID id = eventoMp("ERRO");
        HttpResponse<String> r = reprocessar(id, cookie, false);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(codigo(r)).isEqualTo("csrf_invalido");
        assertThat(status(id)).isEqualTo("ERRO");
        assertThat(contar("select count(*) from django_admin_log")).isZero();
    }

    /** Reentrega do MP e reprocesso do admin do mesmo evento juntos: uma vez so, como dois webhooks concorrentes. */
    @Test
    void reprocessoConcorrenteComReentregaDoMp() throws Exception {
        UUID id = eventoMp("ERRO");
        CountDownLatch largada = new CountDownLatch(1);
        List<String> respostas = new ArrayList<>();
        try (var threads = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> admin = threads.submit(() -> {
                largada.await();
                return reprocessar(id);
            });
            Future<HttpResponse<String>> mp = threads.submit(() -> {
                largada.await();
                return notificar(777);
            });
            largada.countDown();
            for (Future<HttpResponse<String>> f : List.of(admin, mp)) {
                HttpResponse<String> r = f.get();
                assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
                respostas.add(JSON.readTree(r.body()).get("status").asString());
            }
        }
        assertThat(respostas).containsExactlyInAnyOrder("ok", "already_processed");
        assertThat(status(id)).isEqualTo("PROCESSADO");
        assertThat(contar("select count(*) from instituicoes_repasse")).isEqualTo(1);
        assertThat(contar("select count(*) from users_user where email = 'maria@exemplo.com'")).isEqualTo(1);
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'gerar-oracao'")).isEqualTo(1);
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'boas-vindas'")).isEqualTo(1);
        assertThat(contar("select count(*) from payments_webhookevento")).isEqualTo(1);
    }

    /** Notificacao assinada do MP com o id externo do evento ja gravado (a reentrega). */
    HttpResponse<String> notificar(long id) throws Exception {
        String ts = "1762434000";
        String requestId = "req-" + id;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SEGREDO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String v1 = HexFormat.of().formatHex(mac.doFinal(("id:" + PAGAMENTO + ";request-id:" + requestId + ";ts:" + ts
                + ";").getBytes(StandardCharsets.UTF_8)));
        String corpo = "{\"id\": " + id + ", \"type\": \"payment\", \"action\": \"payment.updated\", "
                + "\"data\": {\"id\": \"" + PAGAMENTO + "\"}}";
        return http.send(HttpRequest.newBuilder(uri("/api/webhooks/mercadopago/?data.id=" + PAGAMENTO
                + "&type=payment")).header("Content-Type", "application/json").header("x-request-id", requestId)
                .header("x-signature", "ts=" + ts + ",v1=" + v1).POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
