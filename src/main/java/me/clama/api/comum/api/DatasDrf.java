package me.clama.api.comum.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formato de data/hora dos serializers do DRF, compartilhado por todos os modulos. */
public final class DatasDrf {
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo"); // TIME_ZONE do Django
    private static final DateTimeFormatter SEGUNDOS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private DatasDrf() {}

    /** DateTimeField do DRF: fuso local + isoformat() do Python (micro so se houver, "+00:00" vira "Z"). */
    public static String dataHora(OffsetDateTime t) {
        if (t == null) {
            return null;
        }
        OffsetDateTime local = t.atZoneSameInstant(FUSO).toOffsetDateTime();
        int micro = local.getNano() / 1000;
        return local.format(SEGUNDOS) + (micro == 0 ? "" : String.format(".%06d", micro)) + local.getOffset().getId();
    }
}
