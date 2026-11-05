package me.clama.api.comum.cripto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashIdentificadorTest {
    private final HashIdentificador h = new HashIdentificador((String) CompatCripto.secao("hmac").get("segredo"));

    @Test
    void bateComPython() {
        for (var c : CompatCripto.casos("hmac")) {
            String e = c.get("entrada");
            String r = switch (c.get("tipo")) {
                case "email" -> h.hashEmail(e);
                case "cpf" -> h.hashCpfCnpj(e);
                case "telefone" -> h.hashTelefone(e);
                case "ip" -> h.hashIp(e);
                default -> throw new IllegalStateException(c.get("tipo"));
            };
            assertThat(r).as(c.get("tipo") + " " + e).hasSize(64).isEqualTo(c.get("hash"));
        }
    }
}
