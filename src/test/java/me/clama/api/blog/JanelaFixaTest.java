package me.clama.api.blog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import me.clama.api.RelogioDeTeste;
import org.junit.jupiter.api.Test;

class JanelaFixaTest {
    static final long TS = Instant.parse("2026-11-20T15:00:00Z").getEpochSecond();

    /** _get_window do django-ratelimit: zlib.crc32(b"2") % 60 == 37, crc32(b"1") % 60 == 23, crc32(b"7") % 60 == 6. */
    @Test
    void fimDaJanelaComoODjangoRatelimit() {
        assertThat(JanelaFixa.fim(TS, 2)).isEqualTo(TS + 37);
        assertThat(JanelaFixa.fim(TS + 37, 2)).isEqualTo(TS + 37); // fim == ts ainda e a mesma janela
        assertThat(JanelaFixa.fim(TS + 38, 2)).isEqualTo(TS + 97); // passou: soma 60
        assertThat(JanelaFixa.fim(TS + 59, 1)).isEqualTo(TS + 83);
        assertThat(JanelaFixa.fim(TS, 7)).isEqualTo(TS + 6);
    }

    @Test
    void todaChamadaContaEOSextoPassaDoLimite() {
        RelogioDeTeste.Fixavel relogio = new RelogioDeTeste.Fixavel();
        relogio.fixar(Instant.ofEpochSecond(TS));
        JanelaFixa j = new JanelaFixa(relogio);
        for (int i = 0; i < 5; i++) {
            assertThat(j.excedeu("comentario", 2, 5)).isFalse();
        }
        assertThat(j.excedeu("comentario", 2, 5)).isTrue();
        assertThat(j.excedeu("comentario", 2, 5)).isTrue(); // o 429 tambem conta
        assertThat(j.excedeu("curtida", 2, 5)).isFalse(); // grupo proprio
        assertThat(j.excedeu("comentario", 3, 5)).isFalse(); // usuario proprio
        relogio.fixar(Instant.ofEpochSecond(TS + 37)); // ainda a janela que fecha em ts + 37
        assertThat(j.excedeu("comentario", 2, 5)).isTrue();
        relogio.fixar(Instant.ofEpochSecond(TS + 38)); // janela nova (fecha em ts + 97)
        assertThat(j.excedeu("comentario", 2, 5)).isFalse();
        j.limpar();
        relogio.fixar(Instant.ofEpochSecond(TS));
        for (int i = 0; i < 5; i++) {
            assertThat(j.excedeu("comentario", 2, 5)).isFalse();
        }
    }
}
