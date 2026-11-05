package me.clama.api.comum.ratelimit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import me.clama.api.comum.erro.PastoralException;
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
        LimiteDeTaxa escopo = metodo.getMethodAnnotation(LimiteDeTaxa.class);
        if (escopo != null) {
            String chave = usuario != null ? String.valueOf(usuario.getId()) : ClienteIp.de(req);
            checar(escopo.escopo(), chave, escopo.limite(), Duration.ofSeconds(escopo.janelaSegundos()));
        } else if (usuario == null && anonimoAtivo) {
            checar("anon", ClienteIp.de(req), 60, MINUTO);
        }
        return true;
    }

    private void checar(String escopo, String chave, int limite, Duration janela) {
        limiter.permitir(escopo, chave, limite, janela).ifPresent(s -> {
            throw PastoralException.limiteExcedido(s);
        });
    }

    private static Usuario usuarioLogado() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof Usuario u ? u : null;
    }
}
