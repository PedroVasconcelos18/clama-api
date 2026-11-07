package me.clama.api.blog.tarefas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * A chave de cache "blog:reconciliacao:rodadas_divergentes" do Django: rodadas seguidas com divergencia, escrita pela
 * reconciliacao e lida pelo verificar-urls. Expira 3600 s depois da ultima escrita (o cache.set renova o TTL).
 * ponytail: em memoria, uma instancia so (como a JanelaFixa); um restart zera, e o alerta atrasa uma rodada.
 */
@Component
public class ContadorDeDivergencia {
    static final Duration TTL = Duration.ofSeconds(3600);

    private final Clock relogio;
    private int rodadas;
    private Instant expiraEm = Instant.MIN;

    ContadorDeDivergencia(Clock relogio) {
        this.relogio = relogio;
    }

    /** cache.get(...) or 0 */
    public synchronized int valor() {
        return relogio.instant().isBefore(expiraEm) ? rodadas : 0;
    }

    /** (cache.get or 0) + 1, gravado com TTL novo. */
    synchronized int somar() {
        rodadas = valor() + 1;
        expiraEm = relogio.instant().plus(TTL);
        return rodadas;
    }

    /** cache.delete; tambem o gancho do replay (cache.clear() do gravador). */
    public synchronized void limpar() {
        rodadas = 0;
        expiraEm = Instant.MIN;
    }
}
