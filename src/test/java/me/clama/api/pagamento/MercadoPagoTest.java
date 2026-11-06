package me.clama.api.pagamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import me.clama.api.pedido.Pedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Nunca chama o Mercado Pago: HttpServer local no lugar de api.mercadopago.com. */
class MercadoPagoTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID ID = UUID.fromString("77777777-7777-4777-8777-000000000001");
    static final String PIX_CRIADO;

    static {
        try {
            PIX_CRIADO = Files.readString(Path.of("src/test/resources/contrato/pedidos/mercadopago/pix_criado.json"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record Recebida(String metodo, String caminho, Map<String, List<String>> headers, String corpo) {
        String header(String nome) {
            List<String> v = headers.get(nome);
            return v == null ? null : v.getFirst();
        }
    }

    record Resposta(int status, String corpo, long atrasoMs) {}

    HttpServer server;
    final List<Recebida> recebidas = new CopyOnWriteArrayList<>();
    final List<Duration> esperas = new ArrayList<>();
    final AtomicInteger chamadas = new AtomicInteger();
    volatile List<Resposta> respostas = List.of(new Resposta(201, PIX_CRIADO, 0));

    @BeforeEach
    void sobe() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool()); // a resposta lenta nao segura a proxima
        server.createContext("/", ex -> {
            recebidas.add(new Recebida(ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                    Map.copyOf(ex.getRequestHeaders()),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int n = chamadas.getAndIncrement();
            Resposta r = respostas.get(Math.min(n, respostas.size() - 1));
            try {
                Thread.sleep(r.atrasoMs());
                byte[] b = r.corpo().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(r.status(), b.length);
                ex.getResponseBody().write(b);
            } catch (Exception cliente) { // o cliente desistiu (timeout)
            } finally {
                ex.close();
            }
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    MercadoPago mp(String token, String backend) {
        return new MercadoPago(token, "http://127.0.0.1:" + server.getAddress().getPort(), backend,
                Duration.ofMillis(300), esperas::add);
    }

    MercadoPago mp() {
        return mp("test", "https://api.clama.test/");
    }

    static Pedido pedido(int centavos, String cpfCnpj) {
        Pedido p = new Pedido();
        ReflectionTestUtils.setField(p, "id", ID);
        p.setNome("  Maria Silva ");
        p.setEmail("maria@exemplo.com");
        p.setCpfCnpj(cpfCnpj);
        p.setValorCentavos(centavos);
        return p;
    }

    static Pedido pedido() {
        return pedido(3000, "52998224725");
    }

    JsonNode corpo(int i) {
        return JSON.readTree(recebidas.get(i).corpo());
    }

    @Test
    void criaPixComOPayloadDoDjango() {
        CobrancaPix c = mp().criarCobrancaPix(pedido());

        assertThat(c.providerPaymentId()).isEqualTo("1325814423");
        assertThat(c.pixQrCode()).startsWith("00020126380014br.gov.bcb.pix");
        assertThat(c.pixQrCodeBase64()).startsWith("iVBORw0KGgo");
        assertThat(c.ticketUrl()).isEqualTo("https://www.mercadopago.com.br/payments/1325814423/ticket?caller_id=1&hash=abc");
        assertThat(recebidas).singleElement().satisfies(r -> {
            assertThat(r.metodo()).isEqualTo("POST");
            assertThat(r.caminho()).isEqualTo("/v1/payments");
            assertThat(r.header("Authorization")).isEqualTo("Bearer test");
            assertThat(r.header("Content-type")).startsWith("application/json");
            assertThat(r.header("X-idempotency-key")).isEqualTo("clama-pedido-" + ID);
        });
        assertThat(corpo(0)).isEqualTo(JSON.readTree("""
                {"transaction_amount": 30.00, "description": "Pedido Clama #77777777", "payment_method_id": "pix",
                 "external_reference": "77777777-7777-4777-8777-000000000001",
                 "payer": {"email": "maria@exemplo.com", "first_name": "Maria Silva",
                           "identification": {"type": "CPF", "number": "52998224725"}},
                 "notification_url": "https://api.clama.test/api/webhooks/mercadopago/"}"""));
    }

    @Test
    void valorEnviadoEhExato() {
        mp().criarCobrancaPix(pedido(1234, "52998224725"));
        mp().criarCobrancaPix(pedido(2147483647, "52998224725"));
        mp().criarCobrancaPix(pedido(3000, "52998224725"));

        // o numero no JSON, como texto: nunca float (21474836.47 em double vira 2.147483647E7)
        assertThat(recebidas.get(0).corpo()).contains("\"transaction_amount\":12.34,");
        assertThat(recebidas.get(1).corpo()).contains("\"transaction_amount\":21474836.47,");
        assertThat(recebidas.get(2).corpo()).contains("\"transaction_amount\":30.00,");
    }

    @Test
    void cnpjSemNomeESemNotificationUrlForaDeHttps() {
        Pedido p = pedido(5000, "11222333000181");
        p.setNome("   ");
        mp("test", "http://api.clama.test").criarCobrancaPix(p);

        JsonNode c = corpo(0);
        assertThat(c.get("payer")).isEqualTo(JSON.readTree("""
                {"email": "maria@exemplo.com", "identification": {"type": "CNPJ", "number": "11222333000181"}}"""));
        assertThat(c.has("notification_url")).isFalse();
    }

    @Test
    void retryUsaMesmaChaveDeIdempotencia() {
        // 1a tentativa estoura o timeout (o MP pode ter criado o Pix); a 2a recebe 201
        respostas = List.of(new Resposta(201, PIX_CRIADO, 2000), new Resposta(201, PIX_CRIADO, 0));

        CobrancaPix c = mp().criarCobrancaPix(pedido());

        assertThat(c.providerPaymentId()).isEqualTo("1325814423");
        assertThat(recebidas).hasSize(2).allSatisfy(r -> assertThat(r.header("X-idempotency-key"))
                .isEqualTo("clama-pedido-77777777-7777-4777-8777-000000000001"));
        assertThat(recebidas.get(0).corpo()).isEqualTo(recebidas.get(1).corpo());
        assertThat(esperas).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void timeoutDepoisIdempotenciaEmAndamentoDepoisSucessoUsaMesmaChave() {
        // timeout -> 409 ("process has not been completed yet") -> 201, mesma chave nas tres
        respostas = List.of(new Resposta(201, PIX_CRIADO, 2000),
                new Resposta(409, "{\"message\":\"in process\",\"cause\":[]}", 0), new Resposta(201, PIX_CRIADO, 0));

        CobrancaPix c = mp().criarCobrancaPix(pedido());

        assertThat(c.providerPaymentId()).isEqualTo("1325814423");
        assertThat(recebidas).hasSize(3).allSatisfy(r -> assertThat(r.header("X-idempotency-key"))
                .isEqualTo("clama-pedido-77777777-7777-4777-8777-000000000001"));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void status423EhRetentado() {
        respostas = List.of(new Resposta(423, "{}", 0), new Resposta(201, PIX_CRIADO, 0));
        assertThat(mp().criarCobrancaPix(pedido()).providerPaymentId()).isEqualTo("1325814423");
        assertThat(chamadas.get()).isEqualTo(2);
    }

    @Test
    void corpoLentoTambemEstouraOTimeout() throws Exception {
        // headers imediatos, corpo gotejando por mais que o timeout (300 ms)
        server.removeContext("/");
        server.createContext("/", ex -> {
            chamadas.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(201, 1000);
            try {
                ex.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8));
                ex.getResponseBody().flush();
                Thread.sleep(2000);
            } catch (Exception cliente) {
                // cliente desistiu
            }
            ex.close();
        });
        long t0 = System.nanoTime();
        ErroDoProvedor e = (ErroDoProvedor) catchThrowable(() -> mp().criarCobrancaPix(pedido()));
        assertThat(e).isNotNull();
        assertThat(e.upstreamStatus()).isNull();
        assertThat(chamadas.get()).isEqualTo(3);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void cincoXXRetentaTresVezesComEsperas1e2EViraIndisponivel() {
        respostas = List.of(new Resposta(500, "{\"message\":\"Internal server error\",\"cause\":[]}", 0));

        ErroDoProvedor e = (ErroDoProvedor) catchThrowable(() -> mp().criarCobrancaPix(pedido()));

        assertThat(e.getMessage()).isEqualTo("Mercado Pago indisponível em payment_create (HTTP 500)");
        assertThat(e.upstreamStatus()).isEqualTo(500);
        assertThat(recebidas).hasSize(3);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void cincoXXForaDaListaNaoRetenta() {
        respostas = List.of(new Resposta(501, "{}", 0));

        ErroDoProvedor e = (ErroDoProvedor) catchThrowable(() -> mp().criarCobrancaPix(pedido()));

        assertThat(e.getMessage()).isEqualTo("Mercado Pago indisponível em payment_create (HTTP 501)");
        assertThat(recebidas).hasSize(1);
    }

    @Test
    void quatroXXNaoRetentaELevaOCorpo() {
        String corpo = "{\"message\":\"Invalid user identification number\",\"cause\":[{\"code\":2067,"
                + "\"description\":\"Invalid user identification number\"}]}";
        respostas = List.of(new Resposta(400, corpo, 0));

        ErroDoProvedor e = (ErroDoProvedor) catchThrowable(() -> mp().criarCobrancaPix(pedido()));

        assertThat(e.getMessage()).isEqualTo("Erro do Mercado Pago em payment_create (HTTP 400)");
        assertThat(e.upstreamStatus()).isEqualTo(400);
        assertThat(e.upstreamBody()).isEqualTo(corpo);
        assertThat(recebidas).hasSize(1);
        assertThat(esperas).isEmpty();
    }

    @Test
    void timeoutEsgotadoViraInacessivelSemStatus() {
        respostas = List.of(new Resposta(201, PIX_CRIADO, 2000));

        ErroDoProvedor e = (ErroDoProvedor) catchThrowable(() -> mp().criarCobrancaPix(pedido()));

        assertThat(e.getMessage()).isEqualTo("Mercado Pago inacessível em payment_create");
        assertThat(e.upstreamStatus()).isNull();
        assertThat(recebidas).hasSize(3);
    }

    @Test
    void respostaSemQrViraErroSemStatus() {
        respostas = List.of(new Resposta(201, "{\"id\": 1325814425, \"status\": \"pending\"}", 0));

        ErroDoProvedor e = (ErroDoProvedor) catchThrowable(() -> mp().criarCobrancaPix(pedido()));

        assertThat(e.getMessage()).isEqualTo("Resposta do Mercado Pago sem id/QR Pix ao criar cobrança");
        assertThat(e.upstreamStatus()).isNull();
    }

    @Test
    void corpoQueNaoEObjetoEmDoisXXEhInesperado() {
        respostas = List.of(new Resposta(200, "[1]", 0));

        ErroDoProvedor e = (ErroDoProvedor) catchThrowable(() -> mp().criarCobrancaPix(pedido()));

        assertThat(e.getMessage()).isEqualTo("Corpo inesperado do Mercado Pago em payment_create");
        assertThat(e.upstreamStatus()).isEqualTo(200);
    }

    @Test
    void tokenNuncaApareceEmLogNemEmExcecao() {
        String token = "APP_USR-SEGREDO-0123456789";
        Logger raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        raiz.addAppender(logs);
        List<Throwable> erros = new ArrayList<>();
        try {
            for (List<Resposta> caso : List.of(
                    List.of(new Resposta(401, "{\"message\":\"Unauthorized access token\"}", 0)),
                    List.of(new Resposta(500, "{}", 0)),
                    List.of(new Resposta(201, PIX_CRIADO, 2000)),
                    List.of(new Resposta(201, "{\"id\": 1}", 0)),
                    List.of(new Resposta(200, "nao e json", 0)))) {
                respostas = caso;
                chamadas.set(0);
                erros.add(catchThrowable(() -> mp(token, "http://local").criarCobrancaPix(pedido())));
                erros.add(catchThrowable(() -> mp(token, "https://api.clama.test").buscarPagamento("123")));
            }
            server.stop(0); // erro de conexao
            erros.add(catchThrowable(() -> mp(token, "https://api.clama.test").criarCobrancaPix(pedido())));
        } finally {
            raiz.detachAppender(logs);
        }

        erros.removeIf(java.util.Objects::isNull); // buscarPagamento com {"id": 1} e sucesso
        assertThat(erros).hasSize(10).allSatisfy(e -> assertThat(e).isInstanceOf(ErroDoProvedor.class));
        assertThat(logs.list).isNotEmpty().allSatisfy(ev -> {
            assertThat(ev.getFormattedMessage()).doesNotContain(token);
            assertThat(String.valueOf(ev.getKeyValuePairs())).doesNotContain(token);
            for (IThrowableProxy t = ev.getThrowableProxy(); t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(token);
            }
        });
        for (Throwable e : erros) {
            StringWriter s = new StringWriter();
            e.printStackTrace(new PrintWriter(s));
            assertThat(s.toString()).doesNotContain(token);
            assertThat(((ErroDoProvedor) e).upstreamBody()).doesNotContain(token);
        }
    }

    @Test
    void buscarPagamentoMapeiaOStatus() {
        Map<String, MercadoPago.StatusPagamento> casos = Map.of("approved", MercadoPago.StatusPagamento.APROVADO,
                "rejected", MercadoPago.StatusPagamento.RECUSADO, "cancelled", MercadoPago.StatusPagamento.RECUSADO,
                "refunded", MercadoPago.StatusPagamento.RECUSADO, "charged_back", MercadoPago.StatusPagamento.RECUSADO,
                "pending", MercadoPago.StatusPagamento.PENDENTE, "in_process", MercadoPago.StatusPagamento.PENDENTE,
                "authorized", MercadoPago.StatusPagamento.PENDENTE);
        casos.forEach((bruto, esperado) -> {
            respostas = List.of(new Resposta(200,
                    "{\"id\": 123, \"status\": \"" + bruto + "\", \"external_reference\": \"" + ID + "\"}", 0));
            chamadas.set(0);
            assertThat(mp().buscarPagamento("123"))
                    .isEqualTo(new MercadoPago.PagamentoMP(esperado, ID.toString(), bruto));
        });
        assertThat(recebidas).allSatisfy(r -> {
            assertThat(r.metodo()).isEqualTo("GET");
            assertThat(r.caminho()).isEqualTo("/v1/payments/123");
            assertThat(r.header("Authorization")).isEqualTo("Bearer test");
        });
    }

    @Test
    void buscarPagamentoSemStatusEhPendente() {
        respostas = List.of(new Resposta(200, "{\"id\": 123}", 0));
        assertThat(mp().buscarPagamento("123"))
                .isEqualTo(new MercadoPago.PagamentoMP(MercadoPago.StatusPagamento.PENDENTE, null, null));
    }

    @Test
    void buscarPagamentoComErro() {
        respostas = List.of(new Resposta(404, "{\"message\":\"Payment not found\"}", 0));
        assertThatThrownBy(() -> mp().buscarPagamento("999")).isInstanceOf(ErroDoProvedor.class)
                .hasMessage("Erro do Mercado Pago em payment_get (HTTP 404)");
    }
}
