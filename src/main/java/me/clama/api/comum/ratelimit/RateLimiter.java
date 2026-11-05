package me.clama.api.comum.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/**
 * Janela deslizante igual ao SimpleRateThrottle do DRF: guarda so os pedidos aceitos e,
 * quando nega, a espera e janela - (agora - mais_antigo), arredondada pra cima (Throttled faz ceil).
 */
// ponytail: em memoria e com lock global, so vale com uma instancia; trocar por tabela no Postgres se escalar
@Component
public class RateLimiter {
    private final Clock relogio;
    private final Map<String, Escopo> escopos = new HashMap<>();

    private static final class Escopo {
        final Map<String, Deque<Instant>> chaves = new HashMap<>();
        Instant faxina = Instant.EPOCH;
    }

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock relogio) {
        this.relogio = relogio;
    }

    /** cache.clear() do Django: zera todos os escopos (replay de contrato e testes). */
    public synchronized void limpar() {
        escopos.clear();
    }

    /** Vazio = pode passar. Presente = segundos ate liberar. */
    public synchronized OptionalLong permitir(String escopo, String chave, int limite, Duration janela) {
        Instant agora = relogio.instant();
        Instant corte = agora.minus(janela);
        Escopo e = escopos.computeIfAbsent(escopo, k -> new Escopo());
        if (e.faxina.isBefore(corte)) { // no maximo uma varredura por janela: tira chaves sem pedido recente
            e.chaves.values().removeIf(h -> !h.peekFirst().isAfter(corte));
            e.faxina = agora;
        }
        Deque<Instant> h = e.chaves.computeIfAbsent(chave, k -> new ArrayDeque<>());
        while (!h.isEmpty() && !h.peekLast().isAfter(corte)) {
            h.pollLast();
        }
        if (h.size() >= limite) {
            long nanos = janela.minus(Duration.between(h.peekLast(), agora)).toNanos();
            return OptionalLong.of((nanos + 999_999_999L) / 1_000_000_000L);
        }
        h.addFirst(agora);
        return OptionalLong.empty();
    }
}
