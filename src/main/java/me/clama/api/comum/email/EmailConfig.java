package me.clama.api.comum.email;

import me.clama.api.comum.retry.Retentativa;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class EmailConfig {
    /** Sem RESEND_API_KEY o contexto sobe; quem falha e o enviar (como o Anymail). */
    @Bean
    @Profile("!test")
    EnviadorDeEmail resendEnviador(
            @Value("${RESEND_API_KEY:}") String chave,
            @Value("${clama.email.resend-url:https://api.resend.com/}") String url,
            @Value("${DJANGO_DEFAULT_FROM_EMAIL:Clama <oracao@clama.me>}") String de,
            ObjectMapper json) {
        return new ResendEnviador(chave, url, de, json, Retentativa::dormir);
    }

    @Bean
    @Profile("test")
    CaixaDeSaidaEmMemoria caixaDeSaida() {
        return new CaixaDeSaidaEmMemoria();
    }
}
