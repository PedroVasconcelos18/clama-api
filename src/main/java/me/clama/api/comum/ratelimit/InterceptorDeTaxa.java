package me.clama.api.comum.ratelimit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Locale;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.identidade.Usuario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Throttles do DRF. Roda depois da autenticacao e da autorizacao (filtros), como o check_throttles,
 * entao 401 de rota protegida nao gasta cota. Sem @LimiteDeTaxa vale o AnonRateThrottle (60/min por IP,
 * so pra quem nao esta logado).
 */
@Component
public class InterceptorDeTaxa implements HandlerInterceptor, WebMvcConfigurer {
    private static final Duration MINUTO = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final boolean anonimoAtivo;

    // LIMITE_ANONIMO_ATIVO=false no perfil de teste, como o DEFAULT_THROTTLE_CLASSES=[] do test.py
    public InterceptorDeTaxa(RateLimiter limiter, @Value("${LIMITE_ANONIMO_ATIVO:true}") boolean anonimoAtivo) {
        this.limiter = limiter;
        this.anonimoAtivo = anonimoAtivo;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        // so o dispatch original: o /error de um 404 nao e view do DRF e nao pode gastar cota
        if (req.getDispatcherType() != DispatcherType.REQUEST || !(handler instanceof HandlerMethod metodo)) {
            return true;
        }
        Usuario usuario = usuarioLogado();
        LimiteDeTaxa[] escopos = metodo.getMethod().getAnnotationsByType(LimiteDeTaxa.class);
        if (escopos.length > 0) {
            long espera = -1;
            for (LimiteDeTaxa e : escopos) {
                String chave = chave(e.chave(), usuario, req);
                if (chave != null) {
                    espera = Math.max(espera, limiter.permitir(e.escopo(), chave, e.limite(),
                            Duration.ofSeconds(e.janelaSegundos())).orElse(-1));
                }
            }
            if (espera >= 0) {
                throw PastoralException.limiteExcedido(espera);
            }
        } else if (usuario == null && anonimoAtivo) {
            limiter.permitir("anon", ClienteIp.de(req), 60, MINUTO).ifPresent(s -> {
                throw PastoralException.limiteExcedido(s);
            });
        }
        return true;
    }

    /**
     * null = o escopo nao se aplica. ponytail: logado sem e-mail, o EmailScopedThrottle cai no "email" do corpo;
     * aqui fica sem limite (todo usuario tem e-mail).
     */
    private static String chave(LimiteDeTaxa.Chave tipo, Usuario usuario, HttpServletRequest req) {
        if (tipo == LimiteDeTaxa.Chave.USUARIO_OU_IP) {
            return usuario != null ? String.valueOf(usuario.getId()) : ClienteIp.de(req);
        }
        String email = usuario == null || usuario.getEmail() == null ? "" : usuario.getEmail();
        return email.isEmpty() ? null : TextoPython.strip(email.toLowerCase(Locale.ROOT));
    }

    private static Usuario usuarioLogado() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof Usuario u ? u : null;
    }
}
