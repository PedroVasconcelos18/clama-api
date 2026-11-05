package me.clama.api.comum.retry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Igual ao with_retry de clama/core/retry.py: espera entre tentativas, nenhuma depois da ultima falha. */
public final class Retentativa {
    private Retentativa() {}

    public static final List<Duration> ESPERAS_PADRAO =
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

    /** Erro de conexao ou timeout (IOException) e HTTP 5xx; 4xx e o resto nao. */
    public static boolean padrao(Throwable t) {
        if (t instanceof HttpStatusException h) return h.status() >= 500 && h.status() < 600;
        return t instanceof IOException || t instanceof UncheckedIOException;
    }

    public static <T> T executar(Supplier<T> acao, int max, List<Duration> esperas, Predicate<Throwable> retentavel) {
        return executar(acao, max, esperas, retentavel, Retentativa::dormir);
    }

    public static <T> T executar(Supplier<T> acao, int max, List<Duration> esperas,
            Predicate<Throwable> retentavel, Consumer<Duration> dormir) {
        for (int tentativa = 1; ; tentativa++) {
            try {
                return acao.get();
            } catch (RuntimeException e) {
                if (tentativa >= max || !retentavel.test(e)) throw e;
                dormir.accept(esperas.get(Math.min(tentativa - 1, esperas.size() - 1)));
            }
        }
    }

    public static void dormir(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido durante a espera do retry", e);
        }
    }
}
