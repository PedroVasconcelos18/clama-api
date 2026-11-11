package me.clama.pagamento.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Falha do Mercado Pago para resposta HTTP (INV:92): status, mensagem pastoral e aviso no Sentry so quando e configuracao. */
class ErroDoMercadoPagoTest {

	static MercadoPago.Falha falha(Integer status, String descricao) {
		return new MercadoPago.Falha("m", status, descricao, MercadoPago.Falha.quatroXX(status) && MercadoPago.ehDeCredencial(descricao));
	}

	@Test
	void credencialOuTokenAusenteDa503ComAvisoNoSentry() {
		try (var sentry = mockStatic(Sentry.class)) {
			for (var f : new MercadoPago.Falha[] { falha(401, "invalid access token"), falha(403, "Payer COLLECTOR mismatch"),
					falha(400, "Recurso NÃO AUTORIZADO"), new MercadoPago.Falha("sem token", null, null, true) }) {
				var e = Cobrancas.paraResposta(f, UUID.randomUUID());
				assertThat(e.status().value()).as(f.descricao()).isEqualTo(503);
				assertThat(e.mensagemPastoral()).isEqualTo(Cobrancas.PASTORAL_CONFIGURACAO);
			}
			sentry.verify(() -> Sentry.captureMessage(anyString(), eq(SentryLevel.ERROR)), org.mockito.Mockito.times(4));
		}
	}

	@Test
	void outro4xxDa422SemAviso() {
		try (var sentry = mockStatic(Sentry.class)) {
			var e = Cobrancas.paraResposta(falha(400, "Invalid user identification number"), UUID.randomUUID());
			assertThat(e.status().value()).isEqualTo(422);
			assertThat(e.mensagemPastoral()).isEqualTo(Cobrancas.PASTORAL_PADRAO);
			sentry.verifyNoInteractions();
		}
	}

	@Test
	void redeCincoXXOuIdempotenciaEmAndamentoDao503MesmoComPalavraDeCredencial() {
		try (var sentry = mockStatic(Sentry.class)) {
			for (Integer status : new Integer[] { null, 500, 504, 409, 423 }) {
				var e = Cobrancas.paraResposta(falha(status, "invalid access token"), UUID.randomUUID());
				assertThat(e.status().value()).as("%s", status).isEqualTo(503);
				assertThat(e.mensagemPastoral()).isEqualTo(Cobrancas.PASTORAL_PADRAO);
			}
			sentry.verifyNoInteractions();
		}
	}
}
