package me.clama.api.comum.cripto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Segredos sem default: faltando, a aplicacao nao sobe (um segredo de dev regravaria os hashes no @PreUpdate). */
@Configuration
public class CriptoConfig {
    @Bean
    Fernet fernet(@Value("${FIELD_ENCRYPTION_KEY:}") String chave) {
        Fernet f = new Fernet(exigir("FIELD_ENCRYPTION_KEY", chave));
        CampoCriptografado.usar(f);
        return f;
    }

    @Bean
    HashIdentificador hashIdentificador(@Value("${FREEMIUM_HASH_SECRET:}") String segredo) {
        return new HashIdentificador(exigir("FREEMIUM_HASH_SECRET", segredo));
    }

    public static String exigir(String nome, String valor) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(nome + " ausente ou vazia: defina a variavel de ambiente (a mesma do Django)");
        }
        return valor;
    }
}
