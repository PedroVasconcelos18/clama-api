package me.clama.api.comum.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** O relogio injetavel (UTC); os testes o trocam por um fixo. */
@Configuration
public class RelogioConfig {
    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }
}
