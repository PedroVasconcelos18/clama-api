package me.clama.api.comum;

import java.util.Locale;

/** clama/core/money.py: valores em centavos; a string "R$ XX,YY" so na apresentacao. */
public final class Dinheiro {
    private Dinheiro() {}

    public static String centavosParaReaisStr(long centavos) {
        return "R$ " + Math.floorDiv(centavos, 100) + "," + String.format(Locale.ROOT, "%02d", Math.floorMod(centavos, 100));
    }
}
