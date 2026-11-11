package me.clama.pagamento.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pagamento.interno.ProcessamentoDePagamento;
import me.clama.pagamento.interno.ProcessamentoDePagamento.Resultado;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notificacoes do Mercado Pago (a assinatura ja passou pelo {@link AssinaturaMercadoPago}). Sem limite de taxa e sem
 * sessao: o MP reenvia a vontade. Toda notificacao reconhecida responde 200, inclusive a ignorada; 5xx so quando reenviar
 * resolve (MP ou banco fora), para o MP tentar de novo.
 */
@RestController
class WebhookMercadoPagoController {

	static final String CAMINHO = "/api/webhooks/mercadopago";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** {@code ok}, {@code ignored}, {@code already_paid}, {@code already_processed} ou {@code error} (ERRO sem reenvio). */
	record RespostaDoWebhook(String status) {}

	private final ProcessamentoDePagamento processamento;

	WebhookMercadoPagoController(ProcessamentoDePagamento processamento) {
		this.processamento = processamento;
	}

	@PostMapping({ CAMINHO, CAMINHO + "/" }) // a barra final: a URL antiga do painel do MP
	RespostaDoWebhook receber(HttpServletRequest req) throws IOException {
		var corpo = req.getInputStream().readNBytes(AssinaturaMercadoPago.LIMITE_DO_CORPO + 1);
		if (corpo.length > AssinaturaMercadoPago.LIMITE_DO_CORPO) throw AssinaturaMercadoPago.corpoGrandeDemais();
		var payload = objeto(corpo);
		var id = payload == null ? null : texto(payload.get("id"));
		if (id == null || id.isBlank() || id.length() > 64) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "payload_invalido", "Notificação sem id.", "Requisição inválida.");
		}
		var tipo = texto(payload.get("type"));
		var dataId = AssinaturaMercadoPago.parametro(req.getQueryString(), "data.id"); // o assinado; o do corpo nunca vale
		var r = processamento.receber(id, tipo == null ? "" : tipo, dataId, JSON.writeValueAsString(essencial(payload)));
		if (r == Resultado.FALHA_TEMPORARIA) {
			throw new ErroDeNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "falha_temporaria", "Falha temporária; o Mercado Pago reenvia.",
					"Tente novamente em instantes.");
		}
		return new RespostaDoWebhook(r.status);
	}

	/**
	 * O que fica gravado: so {@code id}, {@code type}, {@code action} e {@code data.id}. O corpo nao e assinado, entao o
	 * resto (qualquer coisa que um replay quisesse plantar) nao vai para o banco.
	 */
	private static ObjectNode essencial(JsonNode payload) {
		var r = JSON.createObjectNode();
		for (var campo : new String[] { "id", "type", "action" }) {
			if (payload.has(campo)) r.set(campo, payload.get(campo));
		}
		var dataId = payload.path("data").get("id");
		if (dataId != null) r.putObject("data").set("id", dataId);
		return r;
	}

	/** O corpo se for um objeto JSON; senao nulo (vazio, lista, texto). */
	private static JsonNode objeto(byte[] corpo) {
		try {
			var n = corpo.length == 0 ? null : JSON.readTree(corpo);
			return n != null && n.isObject() ? n : null;
		} catch (RuntimeException naoEJson) {
			return null;
		}
	}

	/** Texto como esta, numero como numero; nulo se ausente ou null. */
	private static String texto(JsonNode v) {
		if (v == null || v.isNull()) return null;
		return v.isString() ? v.asString() : v.toString();
	}
}
