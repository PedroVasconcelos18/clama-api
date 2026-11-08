package me.clama.api.pagamento.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Duplo clique no "gerar Pix": o SELECT ... FOR UPDATE segura o pedido durante a chamada ao Mercado Pago, entao a
 * segunda requisicao espera, ve o Pix gravado e o reaproveita. Exatamente um POST /v1/payments, os dois 200 com o
 * mesmo QR, nenhum 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "MERCADOPAGO_ACCESS_TOKEN=test", "BACKEND_PUBLIC_URL=https://api.clama.test",
        "MERCADOPAGO_MIN_VALOR_CENTAVOS=1", "TURNSTILE_SECRET_KEY="})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class CheckoutConcorrenciaTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger CRIACOES = new AtomicInteger();
    static final HttpServer MP;

    static {
        try {
            byte[] pix = Files.readAllBytes(Path.of("src/test/resources/contrato/pedidos/mercadopago/pix_criado.json"));
            MP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MP.setExecutor(Executors.newCachedThreadPool());
            MP.createContext("/v1/payments", ex -> {
                ex.getRequestBody().readAllBytes();
                CRIACOES.incrementAndGet();
                try {
                    Thread.sleep(800); // a segunda requisicao chega enquanto a primeira segura o lock
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(201, pix.length);
                ex.getResponseBody().write(pix);
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
    PedidoRepository pedidos;

    @Autowired
    PlanoRepository planos;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    RateLimiter limiter;

    UUID semearPedido() {
        UUID plano = UUID.randomUUID();
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem,
                    ativo, visivel) values (now(), now(), ?, 'Pedido de Oração', 2000, 'd', 'simples', 1, true, true)""",
                plano);
        return tx.execute(st -> {
            Pedido p = new Pedido();
            p.setPlano(planos.getReferenceById(plano));
            p.setNome("Maria Silva");
            p.setEmail("maria@exemplo.com");
            p.setCpfCnpj("52998224725");
            p.setValorCentavos(3000);
            return pedidos.saveAndFlush(p).getId();
        });
    }

    @Test
    void checkoutConcorrenteCriaUmPix() throws Exception {
        limiter.limpar();
        UUID id = semearPedido();
        CRIACOES.set(0);
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/pedidos/" + id
                + "/checkout/")).POST(HttpRequest.BodyPublishers.noBody()).build();
        CountDownLatch largada = new CountDownLatch(1);
        Callable<HttpResponse<String>> clique = () -> {
            largada.await();
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        };

        List<HttpResponse<String>> respostas;
        try (var threads = Executors.newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> f = List.of(threads.submit(clique), threads.submit(clique));
            largada.countDown();
            respostas = List.of(f.get(0).get(), f.get(1).get());
        }

        assertThat(CRIACOES.get()).isEqualTo(1);
        assertThat(respostas).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        JsonNode a = JSON.readTree(respostas.get(0).body());
        assertThat(JSON.readTree(respostas.get(1).body())).isEqualTo(a);
        assertThat(a.get("pedido_id").asString()).isEqualTo(id.toString());
        assertThat(a.get("pix_qr_code").asString()).startsWith("00020126380014br.gov.bcb.pix");
        assertThat(jdbc.queryForObject("select provider_payment_id from orders_pedido where id = ?", String.class, id))
                .isEqualTo("1325814423");
    }

    @Autowired
    javax.sql.DataSource dataSource;

    /** Outra transacao segura a linha: o checkout espera o lock_timeout e responde 503 pastoral, nao 500. */
    @Test
    void lockPresoViraPastoral503() throws Exception {
        limiter.limpar();
        UUID id = semearPedido();
        CRIACOES.set(0);
        CountDownLatch preso = new CountDownLatch(1);
        CountDownLatch solta = new CountDownLatch(1);
        Thread segura = Thread.ofPlatform().start(() -> {
            try (var c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (var st = c.prepareStatement("select 1 from orders_pedido where id = ? for update")) {
                    st.setObject(1, id);
                    st.executeQuery();
                }
                preso.countDown();
                solta.await();
                c.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        preso.await();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/pedidos/" + id
                    + "/checkout/")).POST(HttpRequest.BodyPublishers.noBody()).build();
            long t0 = System.nanoTime();
            HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
            assertThat(JSON.readTree(r.body()).at("/error/code").asString()).isEqualTo("payment_provider_error");
            assertThat(ms).as("espera o lock_timeout de 15 s (PedidoRepository.travar)").isBetween(14_000L, 20_000L);
            assertThat(CRIACOES.get()).as("nao chegou ao MP").isZero();
        } finally {
            solta.countDown();
            segura.join();
        }
    }

    /** Django test_estado_parcial_nao_dispara_reuso: so payment_id, sem QR, nao reaproveita; cria a cobranca. */
    @Test
    void estadoParcialSemQrCriaNovaCobranca() throws Exception {
        limiter.limpar();
        UUID id = semearPedido();
        jdbc.update("update orders_pedido set provider_payment_id = 'pref_existing' where id = ?", id);
        CRIACOES.set(0);
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + porta + "/api/pedidos/" + id + "/checkout/"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(CRIACOES.get()).isEqualTo(1);
    }

    /** Django test_checkout::pedido_gerando_returns_409: pedido fora de aguardando_pagamento nao gera Pix. */
    @Test
    void pedidoEmGerandoOracaoDa409SemChamarOProvedor() throws Exception {
        limiter.limpar();
        UUID id = semearPedido();
        jdbc.update("update orders_pedido set status = 'gerando_oracao' where id = ?", id);
        CRIACOES.set(0);
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + porta + "/api/pedidos/" + id + "/checkout/"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
        assertThat(CRIACOES.get()).isZero();
    }
}
