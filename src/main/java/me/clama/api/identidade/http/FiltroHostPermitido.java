package me.clama.api.identidade.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.clama.api.blog.webhook.AssinaturaWordPress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ALLOWED_HOSTS do Django (production.py): DJANGO_ALLOWED_HOSTS separado por virgula, mesmo default. Compara o Host sem a
 * porta e sem diferenciar maiusculas; "*" libera tudo e ".dominio" casa o dominio e seus subdominios (is_same_domain).
 * Fora da lista, ou com Host fora do formato, e o 400 "Bad Request (400)" do DisallowedHost. O Host do healthcheck do
 * Railway passa sempre (ele chega assim, mapa 2.2/2.10). Desligavel por clama.hosts.validar=false (perfil test: o
 * replay manda Host: testserver).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroHostPermitido extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(FiltroHostPermitido.class);
    static final String HOST_DO_HEALTHCHECK = "healthcheck.railway.app";
    /** host_validation_re do Django. */
    private static final Pattern HOST = Pattern.compile(
            "^([a-z0-9.-]+|\\[[a-f0-9]*:[a-f0-9.:]+\\])(:[0-9]+)?$", Pattern.CASE_INSENSITIVE);

    private final boolean validar;
    private final List<String> permitidos;

    FiltroHostPermitido(@Value("${clama.hosts.validar:true}") boolean validar,
            @Value("${DJANGO_ALLOWED_HOSTS:clama.me,www.clama.me,api.clama.me}") String permitidos) {
        this.validar = validar;
        this.permitidos = Arrays.stream(permitidos.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String host = req.getHeader("Host");
        if (!validar || permitido(host)) {
            chain.doFilter(req, resp);
            return;
        }
        LOG.warn("Invalid HTTP_HOST header: '{}'. You may need to add it to ALLOWED_HOSTS.", host);
        resp.setStatus(400);
        resp.setContentType("text/html; charset=utf-8");
        resp.getWriter().write(AssinaturaWordPress.BAD_REQUEST);
    }

    boolean permitido(String host) {
        Matcher m = host == null ? null : HOST.matcher(host);
        if (m == null || !m.matches()) {
            return false;
        }
        String dominio = m.group(1).toLowerCase(Locale.ROOT);
        return dominio.equals(HOST_DO_HEALTHCHECK) || permitidos.stream().anyMatch(p -> p.equals("*")
                || (p.startsWith(".") ? dominio.equals(p.substring(1)) || dominio.endsWith(p) : dominio.equals(p)));
    }
}
