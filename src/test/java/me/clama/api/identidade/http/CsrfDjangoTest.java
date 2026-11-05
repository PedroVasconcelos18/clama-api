package me.clama.api.identidade.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CsrfDjangoTest {
    // django.middleware.csrf: _get_new_csrf_string() e _mask_cipher_secret() do mesmo segredo
    static final String SEGREDO = "7v9DrrxVReYVsDreAHE8gpbD7XAY4VzG";
    static final String MASCARADO = "ZtZRcDjcmWAyoOtF8YgN2uP6xbTS1x6EWOYktUGX30ojGhKJyvKL8JQzuYjGViva";

    @Test
    void novoTemOFormatoDoCookieDoDjango() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String t = CsrfDjango.novo();
            assertThat(t).matches("[a-zA-Z0-9]{32}");
            vistos.add(t);
        }
        assertThat(vistos).hasSize(200);
    }

    @Test
    void confereSegredoPuroOuMascaradoPeloDjango() {
        assertThat(CsrfDjango.confere(SEGREDO, SEGREDO)).isTrue();
        assertThat(CsrfDjango.confere(MASCARADO, SEGREDO)).isTrue();
        assertThat(CsrfDjango.confere(SEGREDO.replace('7', '8'), SEGREDO)).isFalse();
        assertThat(CsrfDjango.confere(SEGREDO + "x", SEGREDO)).isFalse();
        assertThat(CsrfDjango.confere(null, SEGREDO)).isFalse();
        assertThat(CsrfDjango.confere(SEGREDO, null)).isFalse();
    }

    @Test
    void segredoDoCookie() {
        assertThat(CsrfDjango.segredo(SEGREDO)).isEqualTo(SEGREDO);
        assertThat(CsrfDjango.segredo(MASCARADO)).isEqualTo(SEGREDO); // cookie mascarado do Django < 4.0
        // UUID que o Java gravava antes: fora do formato, o /api/csrf/ troca
        assertThat(CsrfDjango.segredo("0b9b8f0e-6f5e-4a3c-9a39-5d1f2b7d8c11")).isNull();
        assertThat(CsrfDjango.segredo("7v9DrrxVReYVsDreAHE8gpbD7XAY4Vz!")).isNull();
    }
}
