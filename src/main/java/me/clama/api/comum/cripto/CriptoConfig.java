package me.clama.api.comum.cripto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CriptoConfig {
    @Bean
    Fernet fernet(@Value("${FIELD_ENCRYPTION_KEY}") String chave) {
        Fernet f = new Fernet(chave);
        CampoCriptografado.usar(f);
        return f;
    }

    @Bean
    HashIdentificador hashIdentificador(@Value("${FREEMIUM_HASH_SECRET:dev-only-do-not-use-in-prod}") String segredo) {
        return new HashIdentificador(segredo);
    }
}
