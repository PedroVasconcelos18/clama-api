package me.clama.api.blog;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import org.springframework.stereotype.Component;

/**
 * O rate limit do django-ratelimit (key="user", block=False) das escritas do widget (R11): janela fixa de 60 s com
 * jitter por usuario, fim = ts - ts % 60 + crc32(str(pk)) % 60 (+60 se ficar antes de ts). Toda chamada conta, inclusive
 * as que levam 429 e as invalidas; passa do limite quando o contador fica maior que ele. Cada grupo (comentario,
 * curtida) tem o seu contador.
 */
// ponytail: em memoria e com lock global, so vale com uma instancia (como o RateLimiter); Redis/tabela se escalar
@Component
public class JanelaFixa {
    private record Chave(String grupo, long pk, long fim) {}

    private final Clock relogio;
    private final Map<Chave, Integer> contadores = new HashMap<>();
    private long faxina;

    public JanelaFixa(Clock relogio) {
        this.relogio = relogio;
    }

    /** Conta esta chamada e diz se ela passou do limite (a limite+1-esima na janela leva 429). */
    public synchronized boolean excedeu(String grupo, long pk, int limite) {
        long ts = relogio.instant().getEpochSecond(); // int(time.time())
        if (ts >= faxina) { // janelas que ja acabaram nao voltam: no maximo uma varredura por minuto
            contadores.keySet().removeIf(k -> k.fim() < ts);
            faxina = ts + 60;
        }
        return contadores.merge(new Chave(grupo, pk, fim(ts, pk)), 1, Integer::sum) > limite;
    }

    /** cache.clear(): o replay de contrato zera entre cenarios. */
    public synchronized void limpar() {
        contadores.clear();
    }

    /** _get_window do django-ratelimit, period 60. */
    static long fim(long ts, long pk) {
        CRC32 crc = new CRC32();
        crc.update(String.valueOf(pk).getBytes(StandardCharsets.UTF_8));
        long w = ts - Math.floorMod(ts, 60) + crc.getValue() % 60;
        return w < ts ? w + 60 : w;
    }
}
