package me.clama.api.comum.texto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextoPythonTest {
    @Test
    void splitSemArgumentoComoNoPython() {
        // python3 -c "print('  Maria　 José\x1c'.split())"  -> ['Maria', 'José']
        assertThat(TextoPython.split("  Maria　 José\u001c")).containsExactly("Maria", "José");
        assertThat(TextoPython.split("  \t\n")).isEmpty();
        assertThat(TextoPython.split("")).isEmpty();
    }
}
