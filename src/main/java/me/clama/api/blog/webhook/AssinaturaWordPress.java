package me.clama.api.blog.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * recusa tudo. Le os bytes do corpo antes de qualquer getParameter (que consumiria um form) e passa adiante um request
 * que os rele: o controller interpreta o mesmo corpo como form ou JSON.
 *
 * <p>Fica dentro da cadeia do Spring Security, antes do FiltroJwtCookie (SegurancaConfig): o middleware do Django vem
 * antes da autenticacao do DRF (assinatura invalida + cookie invalido = este 401), e depois do CORS e dos headers.
 */
@Component
public class AssinaturaWordPress extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(AssinaturaWordPress.class);
    public static final String CAMINHO = "/api/webhooks/wordpress/";
    private static final String PREFIXO = "sha256=";

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
        byte[] corpo = req.getInputStream().readAllBytes();
        if (!valida(segredo, corpo, req.getHeader("X-Clama-Signature"))) {
            String xff = req.getHeader("X-Forwarded-For");
            LOG.warn("wordpress_webhook_auth_failed (event=wordpress_webhook_auth, ok=false, ip={})",
                    xff != null && !xff.isEmpty() ? xff.split(",")[0].strip() : req.getRemoteAddr());
            PastoralExceptionHandler.escrever(resp, new PastoralException(401, "unauthorized",
                    "Authentication required", "Não pudemos confirmar quem enviou essa requisição."));
            return;
        }
        chain.doFilter(new CorpoCru(req, corpo), resp);
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

    /** O request com o corpo ja lido: getInputStream e getReader devolvem os mesmos bytes, quantas vezes for. */
    static final class CorpoCru extends HttpServletRequestWrapper {
        private final byte[] corpo;

        CorpoCru(HttpServletRequest req, byte[] corpo) {
            super(req);
            this.corpo = corpo;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(corpo);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
