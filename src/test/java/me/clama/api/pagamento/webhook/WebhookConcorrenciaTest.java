package me.clama.api.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.senha.SenhaDjango;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Notificacoes repetidas do Mercado Pago chegando juntas, com o mesmo id (reentrega) e com ids diferentes para o
 * mesmo pagamento (payment.created + payment.updated): um Repasse, uma conta, um gerar-oracao, um boas-vindas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "MERCADOPAGO_ACCESS_TOKEN=test", "MERCADOPAGO_WEBHOOK_SECRET=" + WebhookConcorrenciaTest.SEGREDO,
        "TURNSTILE_SECRET_KEY=", "LIMITE_ANONIMO_ATIVO=true"})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class WebhookConcorrenciaTest {
    static final String SEGREDO = "segredo-concorrencia";
    static final String PAGAMENTO = "1325814423";
    static final ObjectMapper JSON = new ObjectMapper();
    static volatile UUID pedidoDoPagamento;
    static final HttpServer MP;

    static {
        try {
            MP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MP.setExecutor(Executors.newCachedThreadPool());
            MP.createContext("/v1/payments/" + PAGAMENTO, ex -> {
                try {
                    Thread.sleep(300); // as notificacoes chegam ao lock do pedido juntas
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] corpo = ("{\"id\": " + PAGAMENTO + ", \"status\": \"approved\", \"external_reference\": \""
                        + pedidoDoPagamento + "\"}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, corpo.length);
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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SenhasTemporarias senhas;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void semear() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE users_user, plans_plan, instituicoes_instituicao, orders_pedido, instituicoes_repasse, "
                + "payments_webhookevento, scheduled_tasks RESTART IDENTITY CASCADE");
        senhas.limpar();
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

    HttpResponse<String> notificar(long id) throws Exception {
        return notificar(id, "payment", null);
    }

    HttpResponse<String> notificar(long id, String tipo, String cookie) throws Exception {
        String ts = "1762434000";
        String requestId = "req-" + id;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SEGREDO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String v1 = HexFormat.of().formatHex(mac.doFinal(("id:" + PAGAMENTO + ";request-id:" + requestId + ";ts:" + ts
                + ";").getBytes(StandardCharsets.UTF_8)));
        String corpo = "{\"id\": " + id + ", \"type\": \"" + tipo + "\", \"action\": \"payment.updated\", "
                + "\"data\": {\"id\": \"" + PAGAMENTO + "\"}}";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta
                + "/api/webhooks/mercadopago/?data.id=" + PAGAMENTO + "&type=" + tipo))
                .header("Content-Type", "application/json").header("x-request-id", requestId)
                .header("x-signature", "ts=" + ts + ",v1=" + v1);
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void notificacoesDuplicadasConcorrentes() throws Exception {
        // tres reentregas da notificacao 1 e as notificacoes 2, 3 e 4 do mesmo pagamento, todas de uma vez
        List<Long> ids = List.of(1L, 1L, 1L, 2L, 3L, 4L);
        CountDownLatch largada = new CountDownLatch(1);
        List<String> status;
        try (var threads = Executors.newFixedThreadPool(ids.size())) {
            List<Future<HttpResponse<String>>> f = ids.stream().map(id -> threads.submit(
                    (Callable<HttpResponse<String>>) () -> {
                        largada.await();
                        return notificar(id);
                    })).toList();
            largada.countDown();
            status = new java.util.ArrayList<>();
            for (Future<HttpResponse<String>> r : f) {
                HttpResponse<String> resp = r.get();
                assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
                status.add(JSON.readTree(resp.body()).get("status").asString());
            }
        }

        assertThat(status).filteredOn("ok"::equals).hasSize(1);
        assertThat(status).filteredOn(s -> !s.equals("ok")).allMatch(s -> s.equals("already_paid")
                || s.equals("already_processed"));
        assertThat(contar("select count(*) from instituicoes_repasse")).isEqualTo(1);
        assertThat(contar("select valor_centavos from instituicoes_repasse")).as("20%% de 3001, piso").isEqualTo(600);
        assertThat(contar("select count(*) from users_user")).isEqualTo(1);
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'gerar-oracao'")).isEqualTo(1);
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'boas-vindas'")).isEqualTo(1);
        assertThat(contar("select count(*) from orders_pedido where status = 'pago' and user_id is not null"))
                .isEqualTo(1);
        assertThat(contar("select count(*) from payments_webhookevento")).isEqualTo(4);
        assertThat(contar("select count(*) from payments_webhookevento where status = 'PROCESSADO'")).isEqualTo(1);
        assertThat(contar("select count(*) from payments_webhookevento where status = 'IGNORADO'")).isEqualTo(3);
        Long usuario = jdbc.queryForObject("select id from users_user", Long.class);
        String senha = senhas.ler("doador:temp_password:" + usuario).orElseThrow();
        assertThat(new SenhaDjango().matches(senha, jdbc.queryForObject("select password from users_user",
                String.class))).isTrue();
    }

    /**
     * Erro inesperado depois de criar a conta: o processamento inteiro volta (pedido, repasse, conta, jobs, senha no
     * cache), o WebhookEvento fica ERRO numa transacao propria e o 500 faz o MP reenviar; o reenvio processa.
     */
    @Test
    void erroInesperadoDesfazTudoEMarcaErro() throws Exception {
        jdbc.execute("""
                create or replace function falha_processado() returns trigger language plpgsql as $$
                begin raise exception 'falha simulada no mark_processed'; end $$""");
        jdbc.execute("""
                create trigger falha_processado before update on payments_webhookevento
                for each row when (new.status = 'PROCESSADO') execute function falha_processado()""");
        HttpResponse<String> r;
        try {
            r = notificar(1);
        } finally {
            jdbc.execute("drop trigger falha_processado on payments_webhookevento");
        }

        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(JSON.readTree(r.body()).get("error").get("code").asString()).isEqualTo("internal_error");
        assertThat(jdbc.queryForObject("select status from payments_webhookevento", String.class)).isEqualTo("ERRO");
        assertThat(jdbc.queryForObject("select error_message from payments_webhookevento", String.class))
                .contains("falha simulada no mark_processed");
        assertThat(jdbc.queryForObject("select status from orders_pedido", String.class))
                .isEqualTo("aguardando_pagamento");
        assertThat(contar("select count(*) from instituicoes_repasse")).isZero();
        assertThat(contar("select count(*) from users_user")).isZero();
        assertThat(contar("select count(*) from scheduled_tasks")).isZero();
        assertThat(senhas.ler("doador:temp_password:1")).as("senha da conta desfeita").isEmpty();

        HttpResponse<String> reenvio = notificar(1);
        assertThat(reenvio.body()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(jdbc.queryForObject("select status from payments_webhookevento", String.class))
                .isEqualTo("PROCESSADO");
        assertThat(contar("select count(*) from users_user")).isEqualTo(1);
    }

    /** throttle_classes = [] e fora da autenticacao por cookie: o MP reenvia a vontade, cookie velho nao da 401. */
    @Test
    void semLimiteDeTaxaNemCookie() throws Exception {
        for (int i = 0; i < 61; i++) {
            HttpResponse<String> r = notificar(100 + i, "merchant_order", "clama_access=expirado");
            assertThat(r.statusCode()).as("notificacao %d: %s", i, r.body()).isEqualTo(200);
            assertThat(r.body()).isEqualTo("{\"status\":\"ignored\"}");
        }
    }
}
