package me.clama.api.conta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import me.clama.api.comum.email.EnviadorDeEmail;
import me.clama.api.comum.email.modelo.ModelosDeEmail;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.oracao.SenhasTemporarias;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Sem senha no cache: no-op silencioso, exceto se o doador nunca entrou (credenciais perdidas = WARN + Sentry). */
class JobBoasVindasSemSenhaTest {
    final TransportEmMemoria sentry = new TransportEmMemoria();
    final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    final SenhasTemporarias senhas = mock(SenhasTemporarias.class);
    final EnviadorDeEmail enviador = mock(EnviadorDeEmail.class);
    final JobBoasVindas job = new JobBoasVindas(usuarios, senhas, mock(ModelosDeEmail.class), enviador,
            Duration.ofSeconds(1));

    @BeforeEach
    void preparar() {
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(sentry.fabrica());
        });
        when(senhas.ler(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void desligarSentry() {
        Sentry.close();
    }

    private void usuario(boolean forcarTroca, OffsetDateTime ultimoLogin) {
        Usuario u = new Usuario();
        u.setForceChangePassword(forcarTroca);
        u.setLastLogin(ultimoLogin);
        when(usuarios.findById(7L)).thenReturn(Optional.of(u));
    }

    @Test
    void doadorQueNuncaEntrouGeraEventoSentry() {
        usuario(true, null);
        job.executar(7L, 0);
        assertThat(sentry.eventos).hasSize(1);
        assertThat(sentry.eventos.get(0).getLevel()).isEqualTo(SentryLevel.ERROR);
        verifyNoInteractions(enviador);
    }

    @Test
    void doadorQueJaEntrouOuTrocouASenhaFicaSoNoLog() {
        usuario(false, null);
        job.executar(7L, 0);
        usuario(true, OffsetDateTime.now());
        job.executar(7L, 0);
        assertThat(sentry.eventos).isEmpty();
    }
}
