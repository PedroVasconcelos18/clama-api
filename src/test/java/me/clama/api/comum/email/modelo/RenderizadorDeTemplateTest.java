package me.clama.api.comum.email.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RenderizadorDeTemplateTest {
    final RenderizadorDeTemplate r = new RenderizadorDeTemplate();

    @Test
    void tagDesconhecidaFalha() {
        assertThatThrownBy(() -> r.renderizarTexto("{% for x in y %}{% endfor %}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.renderizarTexto("{{ x|upper }}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.renderizarTexto("{% if a %}x{% endif %}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.renderizarTexto("{% if a and b %}x", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void autoescapeEVariavelAusente() {
        assertThat(r.renderizarTexto("[{{ a }}][{{b}}]", Map.of("a", "&<>\"'")))
                .isEqualTo("[&amp;&lt;&gt;&quot;&#x27;][]");
    }

    @Test
    void ifComDuasVariaveis() {
        String t = "{% if a and b %}sim{% endif %}.";
        assertThat(r.renderizarTexto(t, Map.of("a", "1", "b", "2"))).isEqualTo("sim.");
        assertThat(r.renderizarTexto(t, Map.of("a", "1", "b", ""))).isEqualTo(".");
        assertThat(r.renderizarTexto(t, Map.of("a", "1"))).isEqualTo(".");
    }
}
