package me.clama.pagamento.interno;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code clama.pagamento.*}. {@code mercadopagoToken} vazio nao impede o boot: o Pix responde 503 e loga o motivo.
 * {@code webhookUrl} so vai ao MP se for https.
 */
@ConfigurationProperties("clama.pagamento")
record PagamentoProps(@DefaultValue("100") long valorMinimoCentavos, @DefaultValue("") String mercadopagoToken,
		@DefaultValue("https://api.mercadopago.com") String mercadopagoUrl, @DefaultValue("10s") Duration mercadopagoTimeout,
		@DefaultValue("") String webhookUrl) {

	/** O token nunca aparece (o toString do record imprimiria todos os campos). */
	@Override
	public String toString() {
		return "PagamentoProps[mercadopagoUrl=" + mercadopagoUrl + ", webhookUrl=" + webhookUrl + "]";
	}
}
