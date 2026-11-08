package me.clama.api.comum.texto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TextoPythonTest {
    @Test
    void splitSemArgumentoComoNoPython() {
        // python3 -c "print('  Maria　 José\x1c'.split())"  -> ['Maria', 'José']
        assertThat(TextoPython.split("  Maria　 José\u001c")).containsExactly("Maria", "José");
        assertThat(TextoPython.split("  \t\n")).isEmpty();
        assertThat(TextoPython.split("")).isEmpty();
    }

    @Test
    void reprComoNoPython() {
        // python3 -c "print([repr(x) for x in [1e-5,1.5e-05,1e16,1.2345e20,1e7,0.0001,123456789012345.6,-2.5e-7]])"
        double[] d = {1e-5, 1.5e-05, 1e16, 1.2345e20, 1e7, 0.0001, 123456789012345.6, -2.5e-7};
        String[] esperado = {"1e-05", "1.5e-05", "1e+16", "1.2345e+20", "10000000.0", "0.0001", "123456789012345.6",
            "-2.5e-07"};
        for (int i = 0; i < d.length; i++) {
            assertThat(TextoPython.reprFloat(d[i])).isEqualTo(esperado[i]);
        }
        // python3 -c "print(repr({'a\"b': 'x\ty', 'k': [True, None, 1.0]}))"
        assertThat(TextoPython.repr(new ObjectMapper().readTree("{\"a\\\"b\": \"x\\ty\", \"k\": [true, null, 1.0]}")))
                .isEqualTo("{'a\"b': 'x\\ty', 'k': [True, None, 1.0]}");
    }

    @Test
    void reprDeStringComoNoPython() {
        // python3 -c "print(repr(\"it's\"), repr('a\"b'), repr('x\\'y\"z'), repr('ação'))"
        assertThat(TextoPython.repr("it's")).isEqualTo("\"it's\"");
        assertThat(TextoPython.repr("a\"b")).isEqualTo("'a\"b'");
        assertThat(TextoPython.repr("x'y\"z")).isEqualTo("'x\\'y\"z'");
        assertThat(TextoPython.repr("KM’s Pela Vida")).isEqualTo("'KM’s Pela Vida'"); // o tipografico nao conta
    }
}
