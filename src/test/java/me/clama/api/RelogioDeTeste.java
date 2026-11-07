package me.clama.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Relogio que o teste congela (freezegun do gravador); fixar(null) volta ao relogio do sistema. */
@TestConfiguration(proxyBeanMethods = false)
public class RelogioDeTeste {
    public static class Fixavel extends Clock {
        private volatile Instant fixo;

        public void fixar(Instant instante) {
            this.fixo = instante;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant f = fixo;
            return f != null ? f : Instant.now();
        }
    }

    @Bean
    @Primary
    Fixavel relogioFixavel() {
        return new Fixavel();
    }
}
