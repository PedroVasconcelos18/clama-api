package me.clama.infra.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResendEmailTest {

	static final Email EMAIL = new Email("a@exemplo.com", "Assunto", "texto", "<p>html</p>");

	final Emails sdk = mock(Emails.class);
	final List<Duration> esperas = new ArrayList<>();
	final ResendEmail envio = new ResendEmail(sdk, "Clama <nao-responda@clama.me>", esperas::add);

	@Test
	void sucessoMandaAChaveDeIdempotenciaNoHeader() throws Exception {
		when(sdk.send(any(CreateEmailOptions.class), any(RequestOptions.class))).thenReturn(new CreateEmailResponse("id1"));
		envio.enviar(EMAIL, "senha/abc");

		var opcoes = ArgumentCaptor.forClass(CreateEmailOptions.class);
		var req = ArgumentCaptor.forClass(RequestOptions.class);
		verify(sdk).send(opcoes.capture(), req.capture());
		assertThat(req.getValue().getIdempotencyKey()).isEqualTo("senha/abc");
		assertThat(opcoes.getValue().getTo()).containsExactly("a@exemplo.com");
		assertThat(opcoes.getValue().getFrom()).isEqualTo("Clama <nao-responda@clama.me>");
		assertThat(opcoes.getValue().getText()).isEqualTo("texto");
		assertThat(opcoes.getValue().getHtml()).isEqualTo("<p>html</p>");
		assertThat(esperas).isEmpty();
	}

	@Test
	void jaEnviadoESucessoSemRetentar() throws Exception {
		when(sdk.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
				.thenThrow(new ResendException("invalid_idempotent_request", 409, "{\"name\":\"invalid_idempotent_request\"}"));
		envio.enviar(EMAIL, "k");
		verify(sdk, times(1)).send(any(CreateEmailOptions.class), any(RequestOptions.class));
	}

	@Test
	void erro5xxTentaTresVezesComBackoffEDepoisFalha() throws Exception {
		when(sdk.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
				.thenThrow(new ResendException(503, "indisponivel"));
		assertThatThrownBy(() -> envio.enviar(EMAIL, "k")).isInstanceOf(IllegalStateException.class);
		verify(sdk, times(3)).send(any(CreateEmailOptions.class), any(RequestOptions.class));
		assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void erro5xxQueSeRecuperaDaSucesso() throws Exception {
		when(sdk.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
				.thenThrow(new ResendException(500, "x")).thenReturn(new CreateEmailResponse("id"));
		envio.enviar(EMAIL, "k");
		verify(sdk, times(2)).send(any(CreateEmailOptions.class), any(RequestOptions.class));
	}

	@Test
	void erro4xxNaoTentaDeNovo() throws Exception {
		when(sdk.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
				.thenThrow(new ResendException(422, "destinatario invalido"));
		assertThatThrownBy(() -> envio.enviar(EMAIL, "k")).isInstanceOf(IllegalStateException.class);
		verify(sdk, times(1)).send(any(CreateEmailOptions.class), any(RequestOptions.class));
		assertThat(esperas).isEmpty();
	}

	@Test
	void semChaveFalhaComMensagemClara() {
		var semChave = new ResendEmail(null, "x", d -> {});
		assertThatThrownBy(() -> semChave.enviar(EMAIL, "k")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("RESEND_API_KEY");
	}
}
