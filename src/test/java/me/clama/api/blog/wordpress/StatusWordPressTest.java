package me.clama.api.blog.wordpress;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatusWordPressTest {
    final TransportEmMemoria sentry = new TransportEmMemoria();

    @BeforeEach
    void abre() {
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(sentry.fabrica());
        });
    }

    @AfterEach
    void fecha() {
        Sentry.close();
    }

    @Test
    void traduzOsOitoConhecidosSemAlertar() {
        assertThat(new String[] {"publish", "draft", "future", "private", "pending", "trash", "auto-draft", "inherit"})
                .extracting(s -> StatusWordPress.traduzir(s))
                .containsExactly("publicado", "rascunho", "agendado", "privado", "pendente", "lixeira", "rascunho",
                        "rascunho");
        assertThat(sentry.eventos).isEmpty();
    }

    @Test
    void desconhecidoViraRascunhoComWarningNoSentryEmReprDoPython() {
        assertThat(StatusWordPress.traduzir("novo")).isEqualTo("rascunho");
        assertThat(sentry.eventos).hasSize(1);
        assertThat(sentry.eventos.get(0).getLevel()).isEqualTo(SentryLevel.WARNING);
        assertThat(sentry.eventos.get(0).getMessage().getFormatted())
                .isEqualTo("Status desconhecido do WordPress: 'novo'");
    }

    @Test
    void reprSegueOPython() {
        assertThat(StatusWordPress.repr("")).isEqualTo("''");
        assertThat(StatusWordPress.repr("it's")).isEqualTo("\"it's\"");
        assertThat(StatusWordPress.repr("a'b\"c")).isEqualTo("'a\\'b\"c'");
        assertThat(StatusWordPress.repr("a\nb\\")).isEqualTo("'a\\nb\\\\'");
    }

    @Test
    void publicadoComSenhaViraPrivadoEOrestoNaoMuda() {
        assertThat(StatusWordPress.efetivo("publish", true)).isEqualTo("privado");
        assertThat(StatusWordPress.efetivo("publish", false)).isEqualTo("publicado");
        assertThat(StatusWordPress.efetivo("draft", true)).isEqualTo("rascunho");
    }
}
