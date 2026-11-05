package me.clama.api.comum.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimiterTest {

    /** Relogio que o teste empurra pra frente. */
    static final class Relogio extends Clock {
        Instant agora = Instant.parse("2026-11-05T12:00:00Z");

        void andar(long segundos) {
            agora = agora.plusSeconds(segundos);
        }

        @Override
        public Instant instant() {
            return agora;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    static final Duration MINUTO = Duration.ofMinutes(1);

    @Test
    void janelaDeslizante() {
        Relogio r = new Relogio();
        RateLimiter rl = new RateLimiter(r);
        for (int i = 0; i < 5; i++) {
            assertThat(rl.permitir("x", "ip", 5, MINUTO)).isEmpty();
        }
        assertThat(rl.permitir("x", "ip", 5, MINUTO)).isEqualTo(OptionalLong.of(60));
        // outra chave e outro escopo nao sao afetados
        assertThat(rl.permitir("x", "outro", 5, MINUTO)).isEmpty();
        assertThat(rl.permitir("y", "ip", 5, MINUTO)).isEmpty();

        r.andar(59);
        assertThat(rl.permitir("x", "ip", 5, MINUTO)).isEqualTo(OptionalLong.of(1));
        r.andar(1); // os 5 de t0 saem da janela (history[-1] <= now - duration)
        assertThat(rl.permitir("x", "ip", 5, MINUTO)).isEmpty();
    }

    @Test
    void esperaContaAPartirDoMaisAntigoDaJanela() {
        Relogio r = new Relogio();
        RateLimiter rl = new RateLimiter(r);
        rl.permitir("x", "ip", 3, MINUTO); // t0
        r.andar(30);
        rl.permitir("x", "ip", 3, MINUTO); // t30
        rl.permitir("x", "ip", 3, MINUTO); // t30
        r.andar(10);
        assertThat(rl.permitir("x", "ip", 3, MINUTO)).isEqualTo(OptionalLong.of(20)); // 60 - (40 - 0)
        r.andar(20); // t60: o de t0 sai, abre 1 vaga
        assertThat(rl.permitir("x", "ip", 3, MINUTO)).isEmpty();
        assertThat(rl.permitir("x", "ip", 3, MINUTO)).isEqualTo(OptionalLong.of(30)); // 60 - (60 - 30)
    }

    @Test
    void esperaFracionadaArredondaPraCima() {
        Relogio r = new Relogio();
        RateLimiter rl = new RateLimiter(r);
        rl.permitir("x", "ip", 1, MINUTO);
        r.agora = r.agora.plusMillis(500);
        assertThat(rl.permitir("x", "ip", 1, MINUTO)).isEqualTo(OptionalLong.of(60)); // ceil(59.5)
    }

    @Test
    void rateLimitUsaXForwardedFor() {
        MockHttpServletRequest a = new MockHttpServletRequest();
        a.setRemoteAddr("10.0.0.1");
        a.addHeader("X-Forwarded-For", "1.1.1.1, 10.0.0.1");
        assertThat(ClienteIp.de(a)).isEqualTo("1.1.1.1,10.0.0.1");

        MockHttpServletRequest b = new MockHttpServletRequest();
        b.setRemoteAddr("10.0.0.1");
        b.addHeader("X-Forwarded-For", "2.2.2.2, 10.0.0.1");
        assertThat(ClienteIp.de(b)).isEqualTo("2.2.2.2,10.0.0.1");

        MockHttpServletRequest semXff = new MockHttpServletRequest();
        semXff.setRemoteAddr("10.0.0.1");
        assertThat(ClienteIp.de(semXff)).isEqualTo("10.0.0.1");

        // dois clientes atras do mesmo proxy nao se bloqueiam
        RateLimiter rl = new RateLimiter(new Relogio());
        assertThat(rl.permitir("anon", ClienteIp.de(a), 1, MINUTO)).isEmpty();
        assertThat(rl.permitir("anon", ClienteIp.de(b), 1, MINUTO)).isEmpty();
        assertThat(rl.permitir("anon", ClienteIp.de(a), 1, MINUTO)).isPresent();
    }
}
