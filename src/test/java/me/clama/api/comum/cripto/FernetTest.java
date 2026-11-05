package me.clama.api.comum.cripto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FernetTest {
    private final String chave = (String) CompatCripto.secao("fernet").get("chave");
    private final Fernet fernet = new Fernet(chave);

    @BeforeEach
    void holder() {
        CampoCriptografado.usar(fernet);
    }

    @Test
    void decifraTokensDoPython() throws Exception {
        for (var c : CompatCripto.casos("fernet")) {
            assertThat(fernet.decifrar(c.get("cifrado"))).isEqualTo(c.get("claro"));
        }
    }

    @Test
    void idaEVolta() throws Exception {
        String claro = "Maria da Conceição 123";
        String token = fernet.cifrar(claro);
        System.out.println("TOKEN_JAVA=" + token);
        assertThat(token).startsWith("gAAAAA");
        assertThat(fernet.decifrar(token)).isEqualTo(claro);
    }

    @Test
    void decifraValorInvalidoDevolveCru() {
        String cru = (String) CompatCripto.secao("fernet").get("invalido_cru");
        assertThat(new CampoCriptografado().convertToEntityAttribute(cru)).isEqualTo(cru);
        assertThat(new CampoCriptografado().convertToEntityAttribute(null)).isNull();
        assertThat(new CampoCriptografado().convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void rejeitaTokenAdulterado() {
        String t = fernet.cifrar("segredo");
        int i = t.length() / 2;
        String ruim = t.substring(0, i) + (t.charAt(i) == 'A' ? 'B' : 'A') + t.substring(i + 1);
        assertThatThrownBy(() -> fernet.decifrar(ruim)).isInstanceOf(TokenInvalido.class);
    }
}
