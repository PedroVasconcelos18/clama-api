package me.clama.api.comum.cripto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
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

    // Valores gerados no Python (secret "segredo-de-teste"):
    // python -c "import hmac,hashlib;h=lambda v:hmac.new(b'segredo-de-teste',v.encode(),hashlib.sha256).hexdigest();
    //   d=lambda x:''.join(c for c in x if c.isdigit()); print(h(' Ana@Exemplo.com '.strip().lower()), h('IVAN@X.COM'.lower()),
    //   h(d('\uff11\uff12\uff13.\uff14\uff15\uff16')), h(d('+55 \u00b21 \u24602 \u00bd3 \u24694 \U0001d7ce9')), h('  2001:DB8::1 \x85'.strip().lower()))"
    @Test
    void normalizacaoUnicodeBateComPython() {
        Locale padrao = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            assertThat(h.hashEmail(" Ana@Exemplo.com ")).isEqualTo("7638726da71d9e1dcf19daa4e7014f20789cf4aceb57f4b485d9191fe9da2e26");
            assertThat(h.hashEmail("IVAN@X.COM")).isEqualTo("e475504a4865efca153edc3121fa4e333df42f8316225be9459a0b271bab4374");
            assertThat(h.hashCpfCnpj("１２３.４５６")).isEqualTo("cd556978880ae15f12718986442543e4ade3696eecca3ab9b2f9ea8e3cc0bb92");
            assertThat(h.hashTelefone("+55 ²1 ①2 ½3 ⑩4 \uD835\uDFCE9")).isEqualTo("5d5a2c12248c92c6d33ea06ae90dac88f8360765a15ae8212ac22119f19f86a6");
            assertThat(h.hashIp("  2001:DB8::1 \u0085")).isEqualTo("0b138f0154dcedee0098e340a358c956f6b98f58c3410909a5b735ad5e559675");
        } finally {
            Locale.setDefault(padrao);
        }
    }
}
