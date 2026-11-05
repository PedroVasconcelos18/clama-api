package me.clama.api.comum.email.modelo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuotePythonTest {
    @Test
    void igualAoUrllibQuote() {
        // python3 -c "from urllib.parse import quote;print(quote('a b/ç?&\"\n'))"
        assertThat(QuotePython.quote("a b/ç?&\"\n")).isEqualTo("a%20b/%C3%A7%3F%26%22%0A");
        // python3 -c "from urllib.parse import quote;print(quote('A-z_.~0🙏'))"
        assertThat(QuotePython.quote("A-z_.~0🙏")).isEqualTo("A-z_.~0%F0%9F%99%8F");
    }
}
