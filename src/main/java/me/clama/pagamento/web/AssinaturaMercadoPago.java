package me.clama.pagamento.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.web.ErroDeNegocio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Porta do webhook do Mercado Pago, so na cadeia dele (nao e bean: o Boot o registraria em toda requisicao). Antes de
 * qualquer trabalho: corpo acima do teto e 400 (INV:22) e so depois a assinatura, que recusa com 401 (INV:94).
 *
 * <p>Assinatura igual a do sistema antigo: HMAC-SHA256 em hex de {@code id:<data.id minusculo>;request-id:<x-request-id
 * ou "">;ts:<ts>;} com o segredo, {@code ts} e {@code v1} lidos do {@code x-signature} (partes por virgula, com espaco,
 * a ultima chave vale) e {@code data.id} so da query. Comparacao em tempo constante.
 *
 * <p><b>Sem janela de tempo no {@code ts} (D8), de proposito:</b> o estado vem sempre do GET ao MP, nunca do corpo, e o
 * processamento e idempotente; um replay nao muda nada. Uma janela poderia descartar reentregas legitimas do MP feitas
 * com atraso (a documentacao nao diz se o {@code ts} muda nas retentativas).
 */
final class AssinaturaMercadoPago extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(AssinaturaMercadoPago.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** A notificacao do MP tem ~200 bytes (vetores do sistema antigo); 8 KB e folga de sobra. */
	static final int LIMITE_DO_CORPO = 8 * 1024;
	static final String PASTORAL_ASSINATURA = "Não pudemos confirmar quem enviou essa requisição.";

	private final String segredo;

	AssinaturaMercadoPago(String segredo) {
		this.segredo = segredo;
	}

	/**
	 * Segredo em branco faz todo webhook ser recusado e o pagamento nunca confirmar: fora de local/teste o boot para
	 * (como no sistema antigo). Em local e teste sobe, com aviso.
	 */
	static String exigirSegredo(String segredo, Environment env) {
		if (segredo != null && !segredo.isBlank()) return segredo;
		if (!env.acceptsProfiles(Profiles.of("local", "test"))) {
			throw new IllegalStateException("MERCADOPAGO_WEBHOOK_SECRET ausente: sem ele todo webhook do Mercado Pago seria recusado");
		}
		LOG.warn("MERCADOPAGO_WEBHOOK_SECRET ausente: o webhook do Mercado Pago recusa tudo (so reprocessamento)");
		return "";
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
			throws ServletException, IOException {
		// ponytail: so pelo Content-Length; o controlador le no maximo o teto + 1 byte, entao chunked tambem para la
		if (req.getContentLengthLong() > LIMITE_DO_CORPO) {
			LOG.warn("Mercado Pago webhook: corpo acima do limite (event=mercadopago_webhook_auth, ok=false)");
			escrever(resp, corpoGrandeDemais());
			return;
		}
		if (!valida(segredo, req.getQueryString(), req.getHeader("x-request-id"), req.getHeader("x-signature"))) {
			LOG.warn("Mercado Pago webhook: assinatura recusada (event=mercadopago_webhook_auth, ok=false, ip={})", ClienteIp.de(req));
			escrever(resp, new ErroDeNegocio(HttpStatus.UNAUTHORIZED, "assinatura_invalida", "Assinatura do webhook inválida.",
					PASTORAL_ASSINATURA));
			return;
		}
		chain.doFilter(req, resp);
	}

	static ErroDeNegocio corpoGrandeDemais() {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "corpo_grande_demais", "Corpo da notificação acima do limite.",
				"Requisição inválida.");
	}

	/** O mesmo ProblemDetail do {@code TratadorDeErros} (o filtro roda antes do MVC). */
	private static void escrever(HttpServletResponse resp, ErroDeNegocio e) throws IOException {
		resp.setStatus(e.status().value());
		resp.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
		JSON.writeValue(resp.getOutputStream(), Map.of("type", "about:blank", "title", e.status().getReasonPhrase(),
				"status", e.status().value(), "detail", e.getMessage(), "codigo", e.codigo(), "mensagemPastoral",
				e.mensagemPastoral()));
	}

	/** {@code query}: a query string crua. {@code requestId} e {@code assinatura}: os headers, nulos se ausentes. */
	static boolean valida(String segredo, String query, String requestId, String assinatura) {
		if (segredo == null || segredo.isEmpty()) return false;
		var dataId = parametro(query, "data.id");
		String ts = null;
		String v1 = null;
		for (var parte : (assinatura == null ? "" : assinatura).split(",", -1)) {
			var p = parte.strip();
			var igual = p.indexOf('=');
			var chave = igual < 0 ? p : p.substring(0, igual);
			var valor = igual < 0 ? "" : p.substring(igual + 1);
			if (chave.equals("ts")) ts = valor;
			else if (chave.equals("v1")) v1 = valor;
		}
		if (ts == null || ts.isEmpty() || v1 == null || v1.isEmpty() || dataId.isEmpty()) return false;
		var manifesto = "id:" + dataId.toLowerCase(Locale.ROOT) + ";request-id:" + (requestId == null ? "" : requestId)
				+ ";ts:" + ts + ";";
		String esperado;
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			esperado = HexFormat.of().formatHex(mac.doFinal(manifesto.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
		// tempo constante; v1 nao-ASCII nunca bate com o hex
		return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Como o {@code request.GET.get} do Django: so a query, pares por "&", chave e valor decodificados ("+" vira espaco),
	 * o ultimo valor de chave repetida; escape invalido fica como esta.
	 */
	static String parametro(String query, String nome) {
		var valor = "";
		if (query == null) return valor;
		for (var par : query.split("&")) {
			var igual = par.indexOf('=');
			if (decodificar(igual < 0 ? par : par.substring(0, igual)).equals(nome)) {
				valor = igual < 0 ? "" : decodificar(par.substring(igual + 1));
			}
		}
		return valor;
	}

	private static String decodificar(String s) {
		try {
			return URLDecoder.decode(s, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException escapeInvalido) {
			return s.replace('+', ' ');
		}
	}
}
