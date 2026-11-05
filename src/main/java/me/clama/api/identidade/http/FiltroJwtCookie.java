package me.clama.api.identidade.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.erro.PastoralExceptionHandler;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.JwtInvalido;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CookieJWTAuthentication do Django: le clama_access, exige usuario existente e ativo (get_user do simplejwt)
 * e, so pra quem autenticou por cookie, aplica o CSRF do CsrfViewMiddleware em metodo nao seguro.
 * Nao roda nas rotas com authentication_classes vazio (health, csrf, login e refresh).
 */
public class FiltroJwtCookie extends OncePerRequestFilter {
    private static final Set<String> ROTAS_PUBLICAS = Set.of("/api/health/", "/api/csrf/", "/api/admin/auth/login/",
            "/api/admin/auth/refresh/", "/api/customer/auth/login/", "/api/customer/auth/refresh/");
    /** Unica fonte das rotas publicas: o filtro nao autentica nelas e o SegurancaConfig as libera. */
    static final RequestMatcher PUBLICAS = req -> ROTAS_PUBLICAS.contains(req.getRequestURI());
    /** IsClamaAdmin. */
    static final String ADMIN = "ADMIN";
    private static final Set<String> METODOS_SEGUROS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final ServicoDeTokens tokens;
    private final UsuarioRepository usuarios;
    private final CsrfTokenRepository csrf;
    private final List<String> origensExatas;
    private final List<String[]> origensCuringa; // {esquema, netloc sem "*"}
    private final List<String> hostsConfiaveis;

    public FiltroJwtCookie(ServicoDeTokens tokens, UsuarioRepository usuarios, CsrfTokenRepository csrf,
            List<String> origensConfiaveis) {
        this.tokens = tokens;
        this.usuarios = usuarios;
        this.csrf = csrf;
        this.origensExatas = origensConfiaveis.stream().filter(o -> !o.contains("*")).toList();
        this.origensCuringa = origensConfiaveis.stream().filter(o -> o.contains("*")).map(FiltroJwtCookie::partes)
                .filter(p -> p != null).map(p -> new String[] {p[0], semAsteriscos(p[1])}).toList();
        this.hostsConfiaveis = origensConfiaveis.stream().map(FiltroJwtCookie::partes).filter(p -> p != null)
                .map(p -> semAsteriscos(p[1])).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return PUBLICAS.matches(req);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String token = cookie(req, CookiesDeAuth.ACCESS);
        if (token == null || token.isEmpty()) {
            chain.doFilter(req, resp);
            return;
        }
        Usuario usuario;
        try {
            usuario = usuarios.findById(tokens.validarAcesso(token)).filter(Usuario::isActive).orElse(null);
        } catch (JwtInvalido e) {
            usuario = null;
        }
        if (usuario == null) {
            PastoralExceptionHandler.escrever(resp, PastoralException.naoAutenticado());
            return;
        }
        if (!METODOS_SEGUROS.contains(req.getMethod()) && !csrfValido(req)) {
            PastoralExceptionHandler.escrever(resp, PastoralException.csrfInvalido());
            return;
        }
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(usuario, null,
                usuario.isClamaAdmin() ? List.of(new SimpleGrantedAuthority(ADMIN)) : List.of()));
        SecurityContextHolder.setContext(ctx);
        chain.doFilter(req, resp);
    }

    /** CsrfViewMiddleware.process_view do Django 4.2: Origin, senao Referer em https, depois o token. */
    private boolean csrfValido(HttpServletRequest req) {
        String origem = req.getHeader("Origin");
        String host = req.getHeader("Host");
        // o Django confia no X-Forwarded-Proto em producao (SECURE_PROXY_SSL_HEADER); aqui ele so endurece a checagem
        boolean https = req.isSecure() || "https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"));
        if (origem != null) {
            if (!origemConfiavel(origem, https, host)) {
                return false;
            }
        } else if (https && !refererConfiavel(req.getHeader("Referer"), host)) {
            return false;
        }
        CsrfToken salvo = csrf.loadToken(req);
        return salvo != null
                && CsrfDjango.confere(req.getHeader(salvo.getHeaderName()), CsrfDjango.segredo(salvo.getToken()));
    }

    private boolean origemConfiavel(String origem, boolean https, String host) {
        if (host != null && origem.equals((https ? "https://" : "http://") + host)) {
            return true;
        }
        if (origensExatas.contains(origem)) {
            return true;
        }
        String[] p = partes(origem);
        return p != null && origensCuringa.stream().anyMatch(c -> c[0].equals(p[0]) && mesmoDominio(p[1], c[1]));
    }

    private boolean refererConfiavel(String referer, String host) {
        String[] p = referer == null ? null : partes(referer);
        if (p == null || p[1].isEmpty() || !"https".equals(p[0])) {
            return false;
        }
        return hostsConfiaveis.stream().anyMatch(h -> mesmoDominio(p[1], h)) || (host != null && mesmoDominio(p[1], host));
    }

    /** {esquema, netloc} como o urlparse; null se nao tiver "esquema://". */
    private static String[] partes(String url) {
        int i = url.indexOf("://");
        if (i <= 0) {
            return null;
        }
        String resto = url.substring(i + 3);
        int fim = resto.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int k = resto.indexOf(c);
            if (k >= 0 && k < fim) {
                fim = k;
            }
        }
        return new String[] {url.substring(0, i).toLowerCase(Locale.ROOT), resto.substring(0, fim)};
    }

    private static String semAsteriscos(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '*') {
            i++;
        }
        return s.substring(i);
    }

    /** django.utils.http.is_same_domain. */
    private static boolean mesmoDominio(String host, String padrao) {
        if (padrao.isEmpty()) {
            return false;
        }
        padrao = padrao.toLowerCase(Locale.ROOT);
        return padrao.charAt(0) == '.' && (host.endsWith(padrao) || host.equals(padrao.substring(1)))
                || padrao.equals(host);
    }

    private static String cookie(HttpServletRequest req, String nome) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie c : req.getCookies()) {
            if (c.getName().equals(nome)) {
                return c.getValue();
            }
        }
        return null;
    }
}
