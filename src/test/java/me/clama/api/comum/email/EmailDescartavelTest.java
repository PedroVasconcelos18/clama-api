package me.clama.api.comum.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Esperados do Python: .venv/bin/python -c "from clama.freemium.services.email_blacklist import is_disposable; ..."
 * (DJANGO_SETTINGS_MODULE=config.settings.test, FIELD_ENCRYPTION_KEY dummy). A lista tem 121 entradas (len), conferidas abaixo.
 */
class EmailDescartavelTest {
    @Test
    void dominioExatoSubdominioECaixa() {
        assertThat(EmailDescartavel.eh("a@mailinator.com")).isTrue();
        assertThat(EmailDescartavel.eh("a@MAILINATOR.COM")).isTrue();
        assertThat(EmailDescartavel.eh("a@x.y.mailinator.com")).isTrue();
        assertThat(EmailDescartavel.eh("  a@10MinuteMail.com ")).isTrue();
        assertThat(EmailDescartavel.eh("a@b@mailinator.com")).isTrue();
        assertThat(EmailDescartavel.eh("a@.mailinator.com")).isTrue();
    }

    @Test
    void legitimoTldSozinhoEEntradaInvalida() {
        assertThat(EmailDescartavel.eh("a@gmail.com")).isFalse();
        assertThat(EmailDescartavel.eh("a@com")).isFalse();
        assertThat(EmailDescartavel.eh("semarroba")).isFalse();
        assertThat(EmailDescartavel.eh("a@")).isFalse();
        assertThat(EmailDescartavel.eh("")).isFalse();
        assertThat(EmailDescartavel.eh(null)).isFalse();
    }

    @Test
    void listaTemOMesmoTamanhoDaDoPython() {
        assertThat(EmailDescartavel.tamanho()).isEqualTo(TAMANHO_PYTHON);
    }

    static final int TAMANHO_PYTHON = 121;
}
