package me.clama.pagamento.interno;

import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.common.IdentificationRequest;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.payment.PaymentCreateRequest;
import com.mercadopago.client.payment.PaymentPayerRequest;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPDefaultHttpClient;
import com.mercadopago.net.MPHttpClient;
import com.mercadopago.net.MPRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import me.clama.pedido.PedidoCobravel;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PaymentClient} do SDK oficial (3.7.0) por tras de tres operacoes: criar o Pix, ler o status e cancelar.
 *
 * <p>Retentativa nossa (a do SDK fica desligada): 3 tentativas, esperas de 1 e 2 s, em rede/timeout e 409, 423, 500, 502,
 * 503, 504, sempre com a mesma chave de idempotencia. O resto falha na hora. Timeout de 10 s por leitura do socket. O token
 * vai por chamada ({@code MPRequestOptions}); o {@code MercadoPagoConfig} global nunca e tocado. Nada de token nem de
 * corpo cru no log (o corpo de erro do MP pode ecoar dados do pagador): so status e a descricao do erro.
 */
@Component
@EnableConfigurationProperties(PagamentoProps.class)
class MercadoPago {

	private static final Logger LOG = LoggerFactory.getLogger(MercadoPago.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<Integer> RETENTAVEIS = Set.of(409, 423, 500, 502, 503, 504);
	private static final int TENTATIVAS = 3;
	private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
	private static final List<String> PALAVRAS_DE_CREDENCIAL = List.of("credential", "credencial", "unauthorized",
			"não autorizado", "nao autorizado", "forbidden", "access token", "access_token", "invalid_token",
			"invalid access", "collector");

	record Pix(long id, String copiaECola, String qrBase64) {}

	/**
	 * Falha depois das retentativas. {@code status} nulo: rede/timeout, resposta inesperada ou sem token.
	 * {@code configuracao}: credencial recusada ou token ausente, nada que o doador resolva.
	 */
	static final class Falha extends RuntimeException {
		private final Integer status;
		private final String descricao;
		private final boolean configuracao;

		Falha(String mensagem, Integer status, String descricao, boolean configuracao) {
			super(mensagem);
			this.status = status;
			this.descricao = descricao;
			this.configuracao = configuracao;
		}

		Integer status() { return status; }

		String descricao() { return descricao; }

		boolean configuracao() { return configuracao; }

		/** 4xx de dado recusado; 409/423 depois das retentativas e idempotencia em andamento, nao recusa. */
		boolean quatroXX() {
			return quatroXX(status);
		}

		static boolean quatroXX(Integer status) {
			return status != null && status >= 400 && status < 500 && status != 409 && status != 423;
		}
	}

	@FunctionalInterface
	private interface Chamada<T> {
		T executar() throws MPException, MPApiException;
	}

	private final PaymentClient cliente;
	private final PagamentoProps props;
	private final Consumer<Duration> dormir;

	@Autowired
	MercadoPago(PagamentoProps props) {
		this(props, MercadoPago::dormir);
	}

	MercadoPago(PagamentoProps props, Consumer<Duration> dormir) {
		this.props = props;
		this.dormir = dormir;
		this.cliente = new PaymentClient(http(props.mercadopagoUrl()));
		if (props.mercadopagoToken().isBlank()) {
			LOG.error("MERCADOPAGO_ACCESS_TOKEN ausente: toda cobranca Pix vai responder 503 ate configurar");
		}
	}

	Pix criarPix(PedidoCobravel p, String chave, Instant expiraEm) {
		var nome = p.nome() == null ? "" : p.nome().strip();
		var doc = p.cpfCnpj();
		var pagador = PaymentPayerRequest.builder().email(p.email()).firstName(nome.isEmpty() ? null : nome)
				.identification(doc == null ? null
						: IdentificationRequest.builder().type(doc.length() == 14 ? "CNPJ" : "CPF").number(doc).build())
				.build();
		var pedido = PaymentCreateRequest.builder()
				.transactionAmount(BigDecimal.valueOf(p.valorCentavos(), 2)) // centavos exatos, nunca double
				.description("Pedido Clama #" + p.id().toString().substring(0, 8))
				.paymentMethodId("pix")
				.externalReference(p.id().toString())
				.dateOfExpiration(expiraEm.atZone(FUSO).toOffsetDateTime())
				.notificationUrl(urlDeNotificacao())
				.payer(pagador)
				.build();
		var pagamento = chamar("payment_create", () -> cliente.create(pedido, opcoes(Map.of("x-idempotency-key", chave))));
		var dados = pagamento.getPointOfInteraction() == null ? null : pagamento.getPointOfInteraction().getTransactionData();
		if (pagamento.getId() == null || dados == null || dados.getQrCode() == null || dados.getQrCode().isBlank()) {
			throw new Falha("Resposta do Mercado Pago sem id/QR Pix", null, null, false);
		}
		return new Pix(pagamento.getId(), dados.getQrCode(), dados.getQrCodeBase64());
	}

	/** Status bruto do MP ({@code pending}, {@code approved}...); nulo se a resposta nao traz. */
	String status(long id) {
		return chamar("payment_get", () -> cliente.get(id, opcoes(Map.of()))).getStatus();
	}

	void cancelar(long id) {
		chamar("payment_cancel", () -> cliente.cancel(id, opcoes(Map.of())));
	}

	/** Sem https o MP nao entrega webhook; o pedido so anda pelo reprocessamento (dev local). */
	private String urlDeNotificacao() {
		if (props.webhookUrl().startsWith("https://")) return props.webhookUrl();
		LOG.warn("Pix criado SEM notification_url: clama.pagamento.webhook-url nao e https; webhook nao sera entregue "
				+ "(event=pix_notification_url_missing)");
		return null;
	}

	private MPRequestOptions opcoes(Map<String, String> headers) {
		var ms = (int) props.mercadopagoTimeout().toMillis();
		return MPRequestOptions.builder().accessToken(props.mercadopagoToken()).connectionTimeout(ms).socketTimeout(ms)
				.connectionRequestTimeout(ms).maxRetries(0).customHeaders(headers).build();
	}

	private <T> T chamar(String operacao, Chamada<T> chamada) {
		if (props.mercadopagoToken().isBlank()) {
			LOG.error("Pix sem MERCADOPAGO_ACCESS_TOKEN (event=mercadopago_sem_token, operation={})", operacao);
			throw new Falha("Mercado Pago sem token configurado", null, null, true);
		}
		for (int tentativa = 1;; tentativa++) {
			var inicio = System.nanoTime();
			Falha falha;
			boolean retentavel;
			try {
				var resposta = chamada.executar();
				LOG.info("Mercado Pago ok (event=mercadopago_request, operation={}, attempt={}, duration_ms={})", operacao,
						tentativa, (System.nanoTime() - inicio) / 1_000_000);
				return resposta;
			} catch (MPApiException e) {
				var status = e.getStatusCode();
				var descricao = descricao(e.getApiResponse() == null ? null : e.getApiResponse().getContent());
				falha = new Falha("Erro do Mercado Pago em " + operacao + " (HTTP " + status + ")", status, descricao,
						Falha.quatroXX(status) && ehDeCredencial(descricao));
				retentavel = RETENTAVEIS.contains(status); // o SDK devolve falha de rede na conexao como 500 sintetico
			} catch (MPException e) {
				falha = new Falha("Mercado Pago inacessível em " + operacao, null, null, false);
				retentavel = causadoPorRede(e); // corpo lento estoura aqui; JSON invalido nao e retentado
			}
			LOG.warn("Mercado Pago falhou (event=mercadopago_request, operation={}, status={}, attempt={}, duration_ms={}, "
					+ "descricao={})", operacao, falha.status(), tentativa, (System.nanoTime() - inicio) / 1_000_000,
					falha.descricao());
			if (!retentavel || tentativa == TENTATIVAS) throw falha;
			dormir.accept(Duration.ofSeconds(1L << (tentativa - 1)));
		}
	}

	/**
	 * O cliente HTTP do proprio SDK ({@code MPDefaultHttpClient}: status vira excecao tipada), com duas correcoes:
	 * <ul>
	 * <li>base configuravel: o SDK fixa {@code https://api.mercadopago.com} ({@code MercadoPagoConfig.BASE_URL} e
	 * constante) e o cliente padrao so registra https; aqui o Apache aceita http (WireMock nos testes);</li>
	 * <li>cabecalho duplicado: no POST o SDK poe uma {@code X-Idempotency-Key} aleatoria e um {@code Authorization} com o
	 * token global (nulo) alem dos nossos, que chegam em minusculas pelo {@code MPRequestOptions}. HTTP nao diferencia
	 * caixa, entao sairiam dois de cada e o MP escolheria um; fica so o nosso.</li>
	 * </ul>
	 */
	private static MPHttpClient http(String base) {
		var apache = HttpClients.custom().disableCookieManagement().disableRedirectHandling().disableAutomaticRetries()
				.setMaxConnTotal(10).setMaxConnPerRoute(10).build();
		var sdk = new MPDefaultHttpClient(apache) {};
		var raiz = base.replaceAll("/+$", "");
		return req -> sdk.send(MPRequest.builder()
				.uri(req.getUri().startsWith(MercadoPagoConfig.BASE_URL)
						? raiz + req.getUri().substring(MercadoPagoConfig.BASE_URL.length()) : req.getUri())
				.method(req.getMethod()).headers(semDuplicados(req.getHeaders())).payload(req.getPayload())
				.queryParams(req.getQueryParams()).accessToken(req.getAccessToken())
				.connectionTimeout(req.getConnectionTimeout()).connectionRequestTimeout(req.getConnectionRequestTimeout())
				.socketTimeout(req.getSocketTimeout()).build());
	}

	/** Mesmo nome em caixas diferentes: vence o minusculo (o que veio do MPRequestOptions). */
	static Map<String, String> semDuplicados(Map<String, String> headers) {
		var r = new HashMap<String, String>();
		headers.forEach((nome, valor) -> {
			var minusculo = nome.toLowerCase(Locale.ROOT);
			if (nome.equals(minusculo) || !headers.containsKey(minusculo)) r.put(nome, valor);
		});
		return r;
	}

	/** {@code cause[0].description}, senao {@code message}; nulo se o corpo nao for um objeto JSON. */
	static String descricao(String corpo) {
		JsonNode n;
		try {
			n = corpo == null || corpo.isBlank() ? null : JSON.readTree(corpo);
		} catch (RuntimeException naoEhJson) {
			return null;
		}
		if (n == null || !n.isObject()) return null;
		var causa = n.path("cause");
		var texto = causa.isArray() && !causa.isEmpty() && causa.get(0).path("description").isString()
				? causa.get(0).get("description").asString() : n.path("message").isString() ? n.get("message").asString() : null;
		return texto == null ? null : texto.strip().substring(0, Math.min(texto.strip().length(), 200));
	}

	static boolean ehDeCredencial(String descricao) {
		if (descricao == null || descricao.isEmpty()) return false;
		var d = descricao.toLowerCase(Locale.ROOT);
		return PALAVRAS_DE_CREDENCIAL.stream().anyMatch(d::contains);
	}

	private static boolean causadoPorRede(Throwable e) {
		for (var c = e; c != null; c = c.getCause()) if (c instanceof java.io.IOException) return true;
		return false;
	}

	static void dormir(Duration d) {
		try {
			Thread.sleep(d);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
