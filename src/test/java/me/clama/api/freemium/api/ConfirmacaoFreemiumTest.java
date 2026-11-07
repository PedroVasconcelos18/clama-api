package me.clama.api.freemium.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.conta.ProvisionamentoDeConta;
import me.clama.api.freemium.FreemiumBlacklistRepository;
import me.clama.api.freemium.TokenDeConfirmacao;
import me.clama.api.freemium.TokenDeConfirmacaoRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.oracao.SenhasTemporarias;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * /api/freemium/confirmar/ alem do replay do Django (ContratoFreemiumTest): cliques simultaneos, corrida com o webhook
 * pelo mesmo e-mail canonico (decisao R2), rollback de tudo numa falha tardia (R5/R6), token nao-string (R14) e o GET
 * sem efeito no banco.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"FRONTEND_BASE_URL=https://clama.me"})
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class ConfirmacaoFreemiumTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00Z");
    static final String TOKEN = "t".repeat(64);
    static final String LIMPAR = "TRUNCATE scheduled_tasks, freemium_freemiumconfirmationtoken,"
            + " freemium_freemiumblacklist, orders_pedido, users_user, plans_plan CASCADE";
    static final ObjectMapper JSON = new ObjectMapper();

    @Value("${local.server.port}") int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RelogioDeTeste.Fixavel relogio;
    @Autowired RateLimiter limiter;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired TokenDeConfirmacaoRepository tokens;
    @Autowired ProvisionamentoDeConta provisionamento;
    @Autowired HashIdentificador hash;
    @MockitoSpyBean Fila fila;
    @MockitoSpyBean FreemiumBlacklistRepository blacklist;
    @MockitoSpyBean SenhasTemporarias senhas;

    final HttpClient http = HttpClient.newHttpClient();
    final UUID plano = UUID.fromString("55555555-5555-4555-8555-000000000005");
    UUID pedido;

    @BeforeEach
    void preparar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute(LIMPAR);
        senhas.limpar();
        limiter.limpar();
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem,
                    ativo, visivel) values (now(), now(), ?, 'Gratuito', 0, 'd', 'simples_gratuita', 9, true, false)""",
                plano);
        relogio.fixar(AGORA);
        pedido = pendente("mariasilva@gmail.com", TOKEN);
    }

    @AfterEach
    void soltar() {
        jdbc.execute(LIMPAR);
        relogio.fixar(null);
    }

    UUID pendente(String email, String token) {
        return tx.execute(s -> {
            Pedido p = new Pedido();
            p.setPlano(planos.getReferenceById(plano));
            p.setNome("Maria Silva");
            p.setEmail(email);
            p.setTelefone("+5511999998888");
            p.setCpfCnpj("52998224725");
            p.setIdade(35);
            p.setSexo("feminino");
            p.setPedidoOracao("Peço oração.");
            p.setEhGratuito(true);
            p.setStatus(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
            p.setConsentIp("203.0.113.9");
            p.setDeviceHash("fp-abc123");
            pedidos.saveAndFlush(p);
            TokenDeConfirmacao t = new TokenDeConfirmacao();
            t.setToken(token);
            t.setPedido(p);
            t.setExpiresAt(OffsetDateTime.ofInstant(AGORA, ZoneOffset.UTC).plusHours(23));
            tokens.save(t);
            return p.getId();
        });
    }

    HttpResponse<String> post(String query, String corpo) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/freemium/confirmar/"
                        + query)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> confirmar() throws Exception {
        return post("", "{\"token\": \"" + TOKEN + "\"}");
    }

    HttpResponse<String> get(String query) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/freemium/confirmar/"
                + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static String code(HttpResponse<String> r) {
        return JSON.readTree(r.body()).get("error").get("code").asString();
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** Nada da saga ficou: sem conta, sem blacklist, pedido pendente e solto, token usavel, sem job. */
    void nadaFicou() {
        assertThat(contar("select count(*) from users_user")).isZero();
        assertThat(contar("select count(*) from freemium_freemiumblacklist")).isZero();
        assertThat(contar("select count(*) from scheduled_tasks")).isZero();
        assertThat(contar("select count(*) from orders_pedido where id = ? and user_id is null and status = ?", pedido,
                "aguardando_confirmacao_email")).isEqualTo(1);
        assertThat(contar("select count(*) from freemium_freemiumconfirmationtoken where used_at is null"))
                .isEqualTo(1);
    }

    @Test
    void confirmarDuasVezes() throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        List<HttpResponse<String>> rs;
        try (var threads = Executors.newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> f = List.of(0, 1).stream().map(i -> threads.submit(() -> {
                largada.await();
                return confirmar();
            })).toList();
            largada.countDown();
            rs = List.of(f.get(0).get(), f.get(1).get());
        }
        HttpResponse<String> ok = rs.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
        HttpResponse<String> outro = rs.stream().filter(r -> r != ok).findFirst().orElseThrow();
        assertThat(JSON.readTree(ok.body()).toString())
                .isEqualTo("{\"pedido_id\":\"" + pedido + "\",\"status\":\"gerando_oracao\"}");
        assertThat(ok.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(ok.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(outro.statusCode()).isEqualTo(400);
        assertThat(code(outro)).isEqualTo("confirmation_token_invalido");
        assertThat(outro.headers().firstValue("Referrer-Policy")).hasValue("same-origin");
        assertThat(outro.headers().firstValue("Cache-Control")).isEmpty();

        assertThat(contar("select count(*) from users_user where email = 'mariasilva@gmail.com'"
                + " and force_change_password and freemium_used_at = ?", OffsetDateTime.ofInstant(AGORA,
                ZoneOffset.UTC))).isEqualTo(1);
        assertThat(contar("select count(*) from freemium_freemiumblacklist")).isEqualTo(1);
        assertThat(contar("select count(*) from scheduled_tasks where task_name = 'gerar-oracao'")).isEqualTo(1);
        Long id = jdbc.queryForObject("select id from users_user", Long.class);
        assertThat(contar("select count(*) from orders_pedido where user_id = ? and status = 'gerando_oracao'", id))
                .isEqualTo(1);
        String senha = senhas.ler("freemium:temp_password:" + id).orElseThrow();
        assertThat(new SenhaDjango().matches(senha, jdbc.queryForObject("select password from users_user",
                String.class))).isTrue();
    }

    /**
     * Mesmo e-mail canonico (alias Gmail no pedido pago): o advisory lock conta-doador serializa a saga e o
     * ProvisionamentoDeConta. Saga primeiro: o webhook espera o commit e so vincula (sem lock, inseriria a mesma conta
     * e estouraria o unique). Webhook primeiro: a saga espera e da 409, desfazendo tudo.
     */
    @Test
    void confirmacaoConcorreComWebhook() throws Exception {
        UUID pago = tx.execute(s -> {
            Pedido p = new Pedido();
            p.setPlano(planos.getReferenceById(plano));
            p.setNome("Maria Silva");
            p.setEmail("Maria.Silva+doacao@googlemail.com");
            p.setCpfCnpj("11144477735");
            p.setStatus(StatusPedido.PAGO);
            return pedidos.saveAndFlush(p).getId();
        });

        // saga primeiro: segura a transacao no enfileirar do gerar-oracao, ja com a conta criada
        CountDownLatch naSaga = new CountDownLatch(1);
        doAnswer(inv -> {
            naSaga.countDown();
            Thread.sleep(700);
            return inv.callRealMethod();
        }).when(fila).enfileirar(any(TipoDeJob.class), startsWith("gerar-oracao/"), any(UUID.class));
        try (var threads = Executors.newSingleThreadExecutor()) {
            Future<HttpResponse<String>> saga = threads.submit(this::confirmar);
            assertThat(naSaga.await(10, TimeUnit.SECONDS)).isTrue();
            ProvisionamentoDeConta.Conta conta = tx.execute(s ->
                    provisionamento.provisionarOuVincular(pedidos.travar(pago).orElseThrow()));
            assertThat(saga.get().statusCode()).isEqualTo(200);
            assertThat(conta.criada()).isFalse();
            assertThat(conta.usuario().getEmail()).isEqualTo("mariasilva@gmail.com");
        }
        assertThat(contar("select count(*) from users_user")).isEqualTo(1);
        assertThat(contar("select count(distinct user_id) from orders_pedido where user_id is not null")).isEqualTo(1);
        reset(fila);

        // webhook primeiro: outro pendente com o mesmo e-mail canonico de um terceiro pedido pago
        jdbc.execute("TRUNCATE scheduled_tasks, freemium_freemiumconfirmationtoken, freemium_freemiumblacklist,"
                + " orders_pedido, users_user CASCADE");
        pedido = pendente("joaosouza@gmail.com", TOKEN);
        UUID pago2 = tx.execute(s -> {
            Pedido p = new Pedido();
            p.setPlano(planos.getReferenceById(plano));
            p.setNome("Joao");
            p.setEmail("Joao.Souza@gmail.com");
            p.setStatus(StatusPedido.PAGO);
            return pedidos.saveAndFlush(p).getId();
        });
        CountDownLatch noWebhook = new CountDownLatch(1);
        try (var threads = Executors.newSingleThreadExecutor()) {
            Future<?> webhook = threads.submit(() -> tx.executeWithoutResult(s -> {
                assertThat(provisionamento.provisionarOuVincular(pedidos.travar(pago2).orElseThrow()).criada())
                        .isTrue();
                noWebhook.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(noWebhook.await(10, TimeUnit.SECONDS)).isTrue();
            HttpResponse<String> r = confirmar();
            webhook.get();
            assertThat(r.statusCode()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("freemium_blacklist_hit");
        }
        assertThat(contar("select count(*) from users_user")).isEqualTo(1);
        assertThat(contar("select count(*) from freemium_freemiumblacklist")).isZero();
        assertThat(contar("select count(*) from freemium_freemiumconfirmationtoken where used_at is null"))
                .isEqualTo(1);
    }

    /** Falha no ultimo passo (enfileirar): 500, nada fica, a senha guardada some; o token segue usavel. */
    @Test
    void falhaNaSagaDesfazTudo() throws Exception {
        doAnswer(inv -> {
            throw new IllegalStateException("fila fora");
        }).when(fila).enfileirar(any(TipoDeJob.class), anyString(), any(UUID.class));
        HttpResponse<String> r = confirmar();
        assertThat(r.statusCode()).isEqualTo(500);
        ArgumentCaptor<String> chave = ArgumentCaptor.forClass(String.class);
        verify(senhas).guardar(chave.capture(), anyString());
        assertThat(chave.getValue()).startsWith("freemium:temp_password:");
        assertThat(senhas.ler(chave.getValue())).isEmpty();
        nadaFicou();

        reset(fila);
        limiter.limpar();
        assertThat(confirmar().statusCode()).isEqualTo(200);
        assertThat(contar("select count(*) from freemium_freemiumconfirmationtoken where used_at is not null"))
                .isEqualTo(1);
    }

    /** Decisao R14: o Django da 500; aqui 400 confirmation_token_invalido, sem cair no token da query. */
    @Test
    void tokenNaoStringDa400() throws Exception {
        for (String corpo : List.of("[]", "[\"" + TOKEN + "\"]", "\"" + TOKEN + "\"", "{\"token\": 1}",
                "{\"token\": [\"" + TOKEN + "\"]}", "{\"token\": {\"t\": 1}}", "{\"token\": true}")) {
            limiter.limpar();
            HttpResponse<String> r = post("?token=" + TOKEN, corpo);
            assertThat(r.statusCode()).as(corpo).isEqualTo(400);
            assertThat(code(r)).as(corpo).isEqualTo("confirmation_token_invalido");
        }
        nadaFicou();
    }

    @Test
    void getRedirecionaSemTocarNoBanco() throws Exception {
        HttpResponse<String> r = get("?token=" + TOKEN);
        assertThat(r.statusCode()).isEqualTo(302);
        assertThat(r.headers().firstValue("Location")).hasValue("https://clama.me/confirmar?token=" + TOKEN);
        assertThat(r.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        nadaFicou();
    }

    /** freemium_confirmar_ip: 30/h num balde so para GET e POST. */
    @Test
    void getEPostDividemOBalde() throws Exception {
        for (int i = 0; i < 15; i++) {
            assertThat(get("").statusCode()).isEqualTo(302);
            assertThat(post("", "{}").statusCode()).isEqualTo(400);
        }
        assertThat(get("").statusCode()).isEqualTo(429);
        assertThat(post("", "{}").statusCode()).isEqualTo(429);
    }

    /** Cookie de acesso invalido numa rota aberta e 401 (como no Django), GET e POST. */
    @Test
    void cookieInvalidoDa401() throws Exception {
        var get = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta
                + "/api/freemium/confirmar/?token=" + TOKEN)).header("Cookie", "clama_access=lixo").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var post = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/freemium/confirmar/"))
                .header("Cookie", "clama_access=lixo").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\": \"" + TOKEN + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        for (var r : List.of(get, post)) {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(code(r)).isEqualTo("not_authenticated");
        }
        nadaFicou();
    }

    /**
     * Corrida com outra confirmacao que ja gravou a blacklist (a pre-checagem nao viu): o unique estoura no insert,
     * e o 409 desfaz a conta, a senha e o uso do token.
     */
    @Test
    void uniqueDaBlacklistNaCorridaDa409EDesfazTudo() throws Exception {
        jdbc.update("""
                insert into freemium_freemiumblacklist (id, created_at, updated_at, cpf_hash, email_hash)
                values (gen_random_uuid(), now(), now(), ?, ?)""", hash.hashCpfCnpj("52998224725"), "e".repeat(64));
        doReturn(false).when(blacklist).existeAlgum(anyString(), anyString(), anyString(), any());
        HttpResponse<String> r = confirmar();
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("freemium_blacklist_hit");
        verify(senhas, never()).guardar(anyString(), anyString());
        assertThat(contar("select count(*) from users_user")).isZero();
        assertThat(contar("select count(*) from freemium_freemiumblacklist")).isEqualTo(1); // so a semeada
        assertThat(contar("select count(*) from scheduled_tasks")).isZero();
        assertThat(contar("select count(*) from orders_pedido where id = ? and user_id is null and status = ?", pedido,
                "aguardando_confirmacao_email")).isEqualTo(1);
        assertThat(contar("select count(*) from freemium_freemiumconfirmationtoken where used_at is null"))
                .isEqualTo(1);
    }
}
