package me.clama.api.blog.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.api.comum.api.FormularioDjango;
import me.clama.api.comum.cripto.CriptoConfig;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.erro.PastoralExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * WordPressWebhookAuthMiddleware (blog/middleware.py:49-86) com verificar_assinatura_webhook
 * (services/wordpress_webhook.py:86-137): so no caminho exato, qualquer metodo. HMAC-SHA256 do corpo cru com
 * WORDPRESS_WEBHOOK_SECRET, prefixo "sha256=" opcional, hex minusculo comparado em tempo constante; segredo vazio
 * recusa tudo. Le os bytes do corpo (ate DATA_UPLOAD_MAX_MEMORY_SIZE) antes de qualquer getParameter, que consumiria um
 * form, e os deixa no atributo {@link #CORPO}: o controller interpreta o mesmo corpo como form ou JSON.
 *
 * <p>Fica dentro da cadeia do Spring Security, antes do FiltroJwtCookie (SegurancaConfig): o middleware do Django vem
 * antes da autenticacao do DRF (assinatura invalida + cookie invalido = este 401), e depois do CORS e dos headers.
 */
@Component
public class AssinaturaWordPress extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(AssinaturaWordPress.class);
    public static final String CAMINHO = "/api/webhooks/wordpress/";
    private static final String PREFIXO = "sha256=";
    /** Atributo do request com os bytes do corpo ja lidos e conferidos: o controller le daqui, sem copiar de novo. */
    public static final String CORPO = "me.clama.api.blog.webhook.AssinaturaWordPress.corpo";
    /** A pagina 400 padrao do Django (sem 400.html no projeto). */
    public static final String BAD_REQUEST = "\n<!doctype html>\n<html lang=\"en\">\n<head>\n"
            + "  <title>Bad Request (400)</title>\n</head>\n<body>\n  <h1>Bad Request (400)</h1><p></p>\n</body>\n"
            + "</html>\n";

    private final String segredo;

    @Autowired
    AssinaturaWordPress(@Value("${WORDPRESS_WEBHOOK_SECRET:}") String segredo, Environment env) {
        this(segredo, env.acceptsProfiles(Profiles.of("test")));
    }

    /** Fora do perfil test o segredo e obrigatorio no boot (production.py:83). */
    AssinaturaWordPress(String segredo, boolean perfilTest) {
        this.segredo = perfilTest ? (segredo == null ? "" : segredo)
                : CriptoConfig.exigir("WORDPRESS_WEBHOOK_SECRET", segredo);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !CAMINHO.equals(req.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        // RequestDataTooBig: o request.body do Django recusa acima de DATA_UPLOAD_MAX_MEMORY_SIZE (400 HTML padrao).
        // Pelo Content-Length antes de ler; o chunked e lido ate um byte a mais que o limite, nunca inteiro.
        byte[] corpo = req.getContentLengthLong() > FormularioDjango.MAX_CAMPO ? null
                : req.getInputStream().readNBytes(FormularioDjango.MAX_CAMPO + 1);
        if (corpo == null || corpo.length > FormularioDjango.MAX_CAMPO) {
            LOG.warn("Request body exceeded settings.DATA_UPLOAD_MAX_MEMORY_SIZE. (event=wordpress_webhook_auth, "
                    + "ok=false)");
            resp.setStatus(400);
            resp.setContentType("text/html; charset=utf-8");
            resp.getWriter().write(BAD_REQUEST);
            return;
        }
        if (!valida(segredo, corpo, req.getHeader("X-Clama-Signature"))) {
            String xff = req.getHeader("X-Forwarded-For");
            LOG.warn("wordpress_webhook_auth_failed (event=wordpress_webhook_auth, ok=false, ip={})",
                    xff != null && !xff.isEmpty() ? xff.split(",")[0].strip() : req.getRemoteAddr());
            PastoralExceptionHandler.escrever(resp, new PastoralException(401, "unauthorized",
                    "Authentication required", "Não pudemos confirmar quem enviou essa requisição."));
            return;
        }
        req.setAttribute(CORPO, corpo);
        chain.doFilter(req, resp);
    }

    /** assinatura: o header X-Clama-Signature, null se ausente. */
    public static boolean valida(String segredo, byte[] corpo, String assinatura) {
        if (segredo == null || segredo.isEmpty()) {
            LOG.warn("wordpress_webhook_auth: secret não configurado (event=wordpress_webhook_auth, ok=false)");
            return false;
        }
        if (assinatura == null || assinatura.isEmpty()) {
            LOG.warn("wordpress_webhook_auth: assinatura ausente (event=wordpress_webhook_auth, ok=false)");
            return false;
        }
        if (assinatura.startsWith(PREFIXO)) {
            assinatura = assinatura.substring(PREFIXO.length());
        }
        String esperado;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            esperado = HexFormat.of().formatHex(mac.doFinal(corpo));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        // hmac.compare_digest: tempo constante; nao-ASCII levanta TypeError no Python (falso) e aqui nunca bate o hex
        boolean ok = MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                assinatura.getBytes(StandardCharsets.UTF_8));
        LOG.info("wordpress_webhook_auth (event=wordpress_webhook_auth, ok={})", ok);
        return ok;
    }
}
