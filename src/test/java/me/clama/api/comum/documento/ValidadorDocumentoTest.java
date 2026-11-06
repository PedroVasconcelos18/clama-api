package me.clama.api.comum.documento;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Esperados gerados pelo Python (clama-backend):
 * DJANGO_SETTINGS_MODULE=config.settings.test FIELD_ENCRYPTION_KEY=-dPyxte5HDDyxBQExFzkphbnXt2xhs9FWlMLPFQpeuw=
 * .venv/bin/python -c "from clama.core.validators_documento import *; print(is_valid_cpf('52998224725'), ...)"
 */
class ValidadorDocumentoTest {
    @Test
    void cpf() {
        assertThat(ValidadorDocumento.cpfValido("52998224725")).isTrue();
        assertThat(ValidadorDocumento.cpfValido("11144477735")).isTrue();
        assertThat(ValidadorDocumento.cpfValido("12345678909")).isTrue();
        assertThat(ValidadorDocumento.cpfValido("５２９９８２２４７２５")).isTrue(); // int() do Python aceita digitos largos
        assertThat(ValidadorDocumento.cpfValido("11111111111")).isFalse();
        assertThat(ValidadorDocumento.cpfValido("5299822472")).isFalse();
        assertThat(ValidadorDocumento.cpfValido("529982247255")).isFalse();
        assertThat(ValidadorDocumento.cpfValido("¹¹¹¹¹¹¹¹¹¹¹")).isFalse();
        assertThat(ValidadorDocumento.cpfValido("abcdefghijk")).isFalse();
        assertThat(ValidadorDocumento.cpfValido("")).isFalse();
    }

    @Test
    void cnpj() {
        assertThat(ValidadorDocumento.cnpjValido("11222333000181")).isTrue();
        assertThat(ValidadorDocumento.cnpjValido("11444777000161")).isTrue();
        assertThat(ValidadorDocumento.cnpjValido("12345678000195")).isTrue();
        assertThat(ValidadorDocumento.cnpjValido("00000000000000")).isFalse();
        assertThat(ValidadorDocumento.cnpjValido("11222333000182")).isFalse();
        assertThat(ValidadorDocumento.cnpjValido("1122233300018")).isFalse();
    }

    @Test
    void soDigitosTiraMascaraEEspacos() {
        assertThat(ValidadorDocumento.soDigitos("529.982.247-25")).isEqualTo("52998224725");
        assertThat(ValidadorDocumento.soDigitos("11.222.333/0001-81")).isEqualTo("11222333000181");
        assertThat(ValidadorDocumento.soDigitos("  529 982 247 25  ")).isEqualTo("52998224725");
        assertThat(ValidadorDocumento.soDigitos(null)).isEmpty();
    }
}
