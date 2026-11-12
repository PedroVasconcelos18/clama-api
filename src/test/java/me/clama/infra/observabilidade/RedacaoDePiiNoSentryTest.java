package me.clama.infra.observabilidade;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedacaoDePiiNoSentryTest {

	@Test
	void tiraCorpoCookiesEHeadersDaRequisicao() {
		var req = new Request();
		req.setData(Map.of("email", "ana@exemplo.com"));
		req.setCookies("SESSION=abc");
		req.setHeaders(Map.of("Authorization", "Bearer x", "Cookie", "SESSION=abc"));
		req.setQueryString("email=ana@exemplo.com");
		var evento = new SentryEvent();
		evento.setRequest(req);

		RedacaoDePiiNoSentry.redigir(evento);

		assertThat(req.getData()).isNull();
		assertThat(req.getCookies()).isNull();
		assertThat(req.getHeaders()).isNull();
		assertThat(req.getQueryString()).isNull();
	}

	@Test
	void trocaEmailECpfEmMensagemEExcecao() {
		var evento = new SentryEvent();
		var msg = new Message();
		msg.setMessage("falha para ana.silva+x@exemplo.com.br");
		msg.setFormatted("falha para ana.silva+x@exemplo.com.br");
		evento.setMessage(msg);
		var ex = new SentryException();
		ex.setValue("CPF 123.456.789-09 e 12345678909 invalidos; pedido 1234567890123 fica");
		evento.setExceptions(List.of(ex));

		RedacaoDePiiNoSentry.redigir(evento);

		assertThat(msg.getMessage()).isEqualTo("falha para [EMAIL]");
		assertThat(msg.getFormatted()).isEqualTo("falha para [EMAIL]");
		assertThat(ex.getValue()).isEqualTo("CPF [CPF] e [CPF] invalidos; pedido 1234567890123 fica");
	}

	@Test
	void eventoSemNadaPassa() {
		assertThat(RedacaoDePiiNoSentry.redigir(new SentryEvent())).isNotNull();
	}
}
