package me.clama.api.pagamento.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.Sentry;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import me.clama.api.pagamento.ErroDoProvedor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Mapeamento do PaymentProviderError no CheckoutView: status, pastoral e o alerta no Sentry. */
class CheckoutErroDoProvedorTest {
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> erro(PastoralException e) {
        return (Map<String, Object>) e.corpo().get("error");
    }

    static PastoralException mapear(String mensagem, Integer status, String corpo) {
        return CheckoutController.paraResposta(new ErroDoProvedor(mensagem, status, corpo), UUID.randomUUID());
    }

    @Test
    void credencialEm4xxDa422ComPastoralDeConfiguracaoEAlerta() {
        PastoralException e = mapear("Erro do Mercado Pago em payment_create (HTTP 401)", 401,
                "{\"message\":\"Unauthorized access token\",\"cause\":[{\"code\":2002,\"description\":\"invalid access token\"}]}");
        assertThat(e.status()).isEqualTo(422);
        assertThat(erro(e)).containsEntry("code", "payment_provider_error")
                .containsEntry("message", "Erro do Mercado Pago em payment_create (HTTP 401)")
                .containsEntry("pastoral_message", CheckoutController.MSG_PAYMENT_CONFIG_ERROR);
        assertThat(sentry.eventos).singleElement().satisfies(ev -> assertThat(ev.getMessage().getFormatted())
                .isEqualTo("Mercado Pago admin-side error no checkout (ver logs)"));
    }

    @Test
    void outro4xxDa422ComPastoralPadraoSemAlerta() {
        PastoralException e = mapear("Erro do Mercado Pago em payment_create (HTTP 400)", 400,
                "{\"message\":\"Unauthorized\",\"cause\":[{\"description\":\"Invalid user identification number\"}]}");
        assertThat(e.status()).isEqualTo(422);
        // a causa vence a message: "Unauthorized" na message nao conta
        assertThat(erro(e)).containsEntry("pastoral_message", CheckoutController.PASTORAL_PADRAO);
        assertThat(sentry.eventos).isEmpty();
    }

    @Test
    void semCausaUsaAMessageEPalavraEmQualquerCaixa() {
        assertThat(erro(mapear("x", 403, "{\"message\":\"  Payer COLLECTOR mismatch \"}")))
                .containsEntry("pastoral_message", CheckoutController.MSG_PAYMENT_CONFIG_ERROR);
        assertThat(erro(mapear("x", 400, "{\"message\":\"Recurso NÃO AUTORIZADO\",\"cause\":[]}")))
                .containsEntry("pastoral_message", CheckoutController.MSG_PAYMENT_CONFIG_ERROR);
        assertThat(erro(mapear("x", 400, "unauthorized, mas nao e JSON")))
                .containsEntry("pastoral_message", CheckoutController.PASTORAL_PADRAO);
        assertThat(sentry.eventos).hasSize(2);
    }

    @Test
    void redeCincoXXECorpoInesperadoDao503MesmoComPalavraDeCredencial() {
        for (Integer status : new Integer[] {null, 500, 504, 200}) {
            PastoralException e = mapear("m", status, "{\"message\":\"invalid access token\"}");
            assertThat(e.status()).as("%s", status).isEqualTo(503);
            assertThat(erro(e)).containsEntry("pastoral_message", CheckoutController.PASTORAL_PADRAO);
        }
        assertThat(sentry.eventos).isEmpty();
    }
}
