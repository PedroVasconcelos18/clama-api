package me.clama.api.comum.antibot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Nunca chama a Cloudflare: HttpServer local no lugar do siteverify. */
class TurnstileTest {
    HttpServer server;
    final List<String> corpos = new CopyOnWriteArrayList<>();
    final List<Duration> esperas = new ArrayList<>();
    final AtomicInteger chamadas = new AtomicInteger();
    volatile int[] statusPorChamada = {200};
    volatile String resposta = "{\"success\":true}";

    @BeforeEach
    void sobe() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            corpos.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int n = chamadas.getAndIncrement();
            int st = statusPorChamada[Math.min(n, statusPorChamada.length - 1)];
            byte[] b = resposta.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(st, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    Turnstile turnstile(String segredo, boolean mockPermitido) {
        return new Turnstile(segredo, "http://127.0.0.1:" + server.getAddress().getPort() + "/", mockPermitido, esperas::add);
    }

    @Test
    void sucessoEnviaFormComRemoteip() {
        assertThat(turnstile("seg", false).validar("tok", "203.0.113.9")).isTrue();
        assertThat(corpos).containsExactly("secret=seg&response=tok&remoteip=203.0.113.9");
    }

    @Test
    void semIpNaoEnviaRemoteip() {
        turnstile("seg", false).validar("a b", null);
        assertThat(corpos).containsExactly("secret=seg&response=a+b");
    }

    @Test
    void falhaDaCloudflareRetornaFalse() {
        resposta = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";
        assertThat(turnstile("seg", false).validar("tok", null)).isFalse();
    }

    @Test
    void retry503ComEsperas1e2() {
        statusPorChamada = new int[] {503, 503, 200};
        assertThat(turnstile("seg", false).validar("tok", null)).isTrue();
        assertThat(chamadas.get()).isEqualTo(3);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void esgotaRetriesViraIndisponivel() {
        statusPorChamada = new int[] {503};
        assertThatThrownBy(() -> turnstile("seg", false).validar("tok", null)).isInstanceOf(TurnstileIndisponivel.class);
        assertThat(chamadas.get()).isEqualTo(3);
    }

    @Test
    void erro4xxNaoRetentaEViraIndisponivel() {
        statusPorChamada = new int[] {400};
        resposta = "{\"success\":false}";
        assertThatThrownBy(() -> turnstile("seg", false).validar("tok", null)).isInstanceOf(TurnstileIndisponivel.class);
        assertThat(chamadas.get()).isEqualTo(1);
        assertThat(esperas).isEmpty();
    }

    @Test
    void erroDeRedeViraIndisponivel() {
        server.stop(0);
        assertThatThrownBy(() -> turnstile("seg", false).validar("tok", null)).isInstanceOf(TurnstileIndisponivel.class);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void mockSoNoPerfilTestAceitaTokenNaoVazio() {
        Turnstile t = turnstile("", true);
        assertThat(t.validar("qualquer", null)).isTrue();
        assertThat(t.validar("", null)).isFalse();
        assertThat(chamadas.get()).isZero();
    }

    @Test
    void foraDoPerfilTestSemSegredoFalhaComErroDeConfiguracao() {
        assertThatThrownBy(() -> turnstile("", false).validar("tok", null)).isInstanceOf(IllegalStateException.class);
        assertThat(chamadas.get()).isZero();
    }

    @Test
    void perfilDecideOMockPeloAmbiente() {
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertThat(new Turnstile("", "http://x/", test).validar("t", null)).isTrue();
        assertThatThrownBy(() -> new Turnstile("", "http://x/", new MockEnvironment()).validar("t", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
