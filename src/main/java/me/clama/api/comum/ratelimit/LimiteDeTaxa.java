package me.clama.api.comum.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * throttle_classes do DRF numa rota. Rota com escopo proprio nao conta no limite anonimo. Repetivel: como no
 * check_throttles, todos os escopos sao avaliados (o que passa registra o pedido) e o 429 leva a maior espera.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(LimiteDeTaxa.Varios.class)
public @interface LimiteDeTaxa {
    String escopo();

    int limite();

    long janelaSegundos();

    Chave chave() default Chave.USUARIO_OU_IP;

    enum Chave {
        /** ScopedRateThrottle: id do usuario logado, senao o IP (get_ident). */
        USUARIO_OU_IP,
        /** EmailScopedThrottle: e-mail do usuario logado em minusculas; sem e-mail, sem limite. */
        EMAIL_DO_USUARIO
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Varios {
        LimiteDeTaxa[] value();
    }
}
