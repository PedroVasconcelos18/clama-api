package me.clama.api.blog;

import java.util.function.Supplier;
import me.clama.api.identidade.Usuario;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * IsUnbannedCustomer (permissions.py:28-53), nas regras do SegurancaConfig: anonimo nega (401), admin passa sem olhar
 * ban, ban ativo (revogado_em nulo) da o 403 plano. Como no DRF, roda antes do throttle e do handler.
 */
@Component
public class SemBanimento {
    public static final ErroPlano CORPO = new ErroPlano("customer_banido", "Sua conta foi suspensa do sistema de "
            + "comentários. Entre em contato pelo nosso e-mail de suporte se quiser entender o motivo.");

    /** Vira o 403 {@link #CORPO} no accessDeniedHandler. */
    public static final class Suspenso extends AccessDeniedException {
        Suspenso() {
            super("customer_banido");
        }
    }

    private final CustomerBanidoRepository banidos;

    SemBanimento(CustomerBanidoRepository banidos) {
        this.banidos = banidos;
    }

    public AuthorizationResult decidir(Supplier<? extends Authentication> auth, RequestAuthorizationContext ctx) {
        Authentication a = auth.get();
        if (a == null || !(a.getPrincipal() instanceof Usuario u)) {
            return new AuthorizationDecision(false);
        }
        if (!u.isClamaAdmin() && banidos.existsByCustomer_IdAndRevogadoEmIsNull(u.getId())) {
            throw new Suspenso();
        }
        return new AuthorizationDecision(true);
    }
}
