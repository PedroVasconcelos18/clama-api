package me.clama.api.comum.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * throttle_scope do DRF (ScopedRateThrottle): chave = id do usuario logado, senao o IP.
 * Rota com escopo proprio nao conta no limite anonimo.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LimiteDeTaxa {
    String escopo();

    int limite();

    long janelaSegundos();
}
