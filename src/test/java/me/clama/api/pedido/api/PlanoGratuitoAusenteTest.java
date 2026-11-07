package me.clama.api.pedido.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.Optional;
import me.clama.api.catalogo.plano.PlanoGratuito;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** _get_plano_gratuito: plano ausente vira 503 pastoral e um evento Sentry de nivel error (como no Django). */
class PlanoGratuitoAusenteTest {
    final TransportEmMemoria sentry = new TransportEmMemoria();

    @BeforeEach
    void ligarSentry() {
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(sentry.fabrica());
        });
    }

    @AfterEach
    void desligarSentry() {
        Sentry.close();
    }

    @Test
    void semPlanoGratuitoCapturaEventoDeNivelError() {
        PlanoRepository planos = mock(PlanoRepository.class);
        when(planos.findFirstByComplexidadeAndVisivelFalseAndAtivoTrueOrderByOrdem("simples_gratuita"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(new PlanoGratuito(planos)::obter).isInstanceOfSatisfying(PastoralException.class,
                e -> assertThat(e.status()).isEqualTo(503));
        assertThat(sentry.eventos).hasSize(1);
        assertThat(sentry.eventos.get(0).getLevel()).isEqualTo(SentryLevel.ERROR);
        assertThat(sentry.eventos.get(0).getMessage().getFormatted())
                .isEqualTo("Plan freemium ausente do banco (SIMPLES_GRATUITA, visivel=False, ativo=True)");
    }
}
