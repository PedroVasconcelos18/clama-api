package me.clama.api.pagamento.webhook;

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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.api.blog.webhook.AssinaturaWordPress;
import me.clama.api.comum.api.FormularioDjango;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.erro.PastoralExceptionHandler;
import me.clama.api.comum.texto.TextoPython;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import me.clama.api.comum.cripto.CriptoConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MercadoPagoWebhookAuthMiddleware (payments/middleware.py) com verificar_assinatura_webhook
 * (mercadopago_client.py:246-284): so no caminho exato do webhook, antes de qualquer leitura do corpo. HMAC-SHA256 de
 * {@code id:<data.id em minusculas>;request-id:<x-request-id ou "">;ts:<ts>;} com MERCADOPAGO_WEBHOOK_SECRET; segredo
 * vazio recusa tudo. Paridade: sem janela de tempo no ts (replay aceito, como no Django).
 */
@Component
public class AssinaturaMercadoPago extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(AssinaturaMercadoPago.class);
    static final String CAMINHO = "/api/webhooks/mercadopago/";

    private final String segredo;

    @Autowired
    AssinaturaMercadoPago(@Value("${MERCADOPAGO_WEBHOOK_SECRET:}") String segredo, Environment env) {
        this(segredo, env.acceptsProfiles(Profiles.of("test")));
    }

    /** Fora do perfil test o segredo e obrigatorio no boot (o Django nao sobe sem ele). */
    AssinaturaMercadoPago(String segredo, boolean perfilTest) {
        this.segredo = perfilTest ? (segredo == null ? "" : segredo)
                : CriptoConfig.exigir("MERCADOPAGO_WEBHOOK_SECRET", segredo);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !CAMINHO.equals(req.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        // Endurecimento alem do Django (que nao limita o corpo do MP): corpo acima de DATA_UPLOAD_MAX_MEMORY_SIZE (o
        // teto do webhook do WordPress) leva o mesmo 400, antes do HMAC. O MP manda poucos bytes.
        // ponytail: so pelo Content-Length; chunked passa (o corpo real do MP nunca vem assim). Contar bytes se virar vetor.
        if (req.getContentLengthLong() > FormularioDjango.MAX_CAMPO) {
            LOG.warn("Mercado Pago webhook: corpo acima do limite (event=mercadopago_webhook_auth, ok=false)");
            resp.setStatus(400);
            resp.setContentType("text/html; charset=utf-8");
            resp.getWriter().write(AssinaturaWordPress.BAD_REQUEST);
            return;
        }
        if (!valida(segredo, req.getQueryString(), req.getHeader("x-request-id"), req.getHeader("x-signature"))) {
            String xff = req.getHeader("X-Forwarded-For");
            LOG.warn("Mercado Pago webhook auth failed (event=mercadopago_webhook_auth, ok=false, ip={})",
                    xff != null && !xff.isEmpty() ? xff.split(",")[0].strip() : req.getRemoteAddr());
            PastoralExceptionHandler.escrever(resp, new PastoralException(401, "unauthorized",
                    "Authentication required", "Não pudemos confirmar quem enviou essa requisição."));
            return;
        }
        chain.doFilter(req, resp);
    }

    /** query: a query string crua (getQueryString). requestId e assinatura: os headers, null se ausentes. */
    public static boolean valida(String segredo, String query, String requestId, String assinatura) {
        if (segredo == null || segredo.isEmpty()) {
            LOG.warn("Mercado Pago webhook auth: secret não configurado (event=mercadopago_webhook_auth, ok=false)");
            return false;
        }
        String dataId = parametro(query, "data.id");
        String ts = null;
        String v1 = null;
        // _parse_signature: partes por ",", strip, chave antes do 1o "="; a ultima ocorrencia vence
        for (String parte : (assinatura == null ? "" : assinatura).split(",", -1)) {
            String p = TextoPython.strip(parte);
            int igual = p.indexOf('=');
            String chave = igual < 0 ? p : p.substring(0, igual);
            String valor = igual < 0 ? "" : p.substring(igual + 1);
            if (chave.equals("ts")) {
                ts = valor;
            } else if (chave.equals("v1")) {
                v1 = valor;
            }
        }
        if (ts == null || ts.isEmpty() || v1 == null || v1.isEmpty() || dataId.isEmpty()) {
            LOG.warn("Mercado Pago webhook auth: assinatura ausente ou malformada (event=mercadopago_webhook_auth, "
                    + "ok=false)");
            return false;
        }
        String manifesto = "id:" + dataId.toLowerCase(Locale.ROOT) + ";request-id:" + (requestId == null ? ""
                : requestId) + ";ts:" + ts + ";";
        String esperado;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            esperado = HexFormat.of().formatHex(mac.doFinal(manifesto.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        // hmac.compare_digest: tempo constante; v1 nao-ASCII nunca bate com o hex
        boolean ok = MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                v1.getBytes(StandardCharsets.UTF_8));
        LOG.info("Mercado Pago webhook auth (event=mercadopago_webhook_auth, ok={})", ok);
        return ok;
    }

    /**
     * request.GET.get(nome, ""): so a query string (nunca o corpo), separada por "&", chave e valor decodificados
     * (+ vira espaco), o ULTIMO valor de chave repetida. Escape invalido fica como esta, como no unquote do Python.
     */
    static String parametro(String query, String nome) {
        String valor = "";
        if (query == null) {
            return valor;
        }
        for (String par : query.split("&")) {
            int igual = par.indexOf('=');
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
