package me.clama.api.comum.documento;

import java.util.Arrays;
import me.clama.api.comum.texto.TextoPython;

/** clama/core/validators_documento.py. Opera em code points, como o str do Python (int() aceita digitos Nd de qualquer script). */
public final class ValidadorDocumento {
    private ValidadorDocumento() {}

    private static final int[] PESOS1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] PESOS2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    /** normalizar_documento: so os caracteres onde str.isdigit() e verdadeiro. */
    public static String soDigitos(String v) {
        StringBuilder sb = new StringBuilder();
        if (v != null) v.codePoints().filter(TextoPython::isDigit).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** Valores dos digitos, ou null se algum nao for decimal (o int() do Python levantaria ValueError). */
    private static int[] valores(String s, int tamanho) {
        int[] cps = s.codePoints().toArray();
        if (cps.length != tamanho || !Arrays.stream(cps).allMatch(TextoPython::isDigit)) return null;
        if (Arrays.stream(cps).allMatch(c -> c == cps[0])) return null; // sequencia repetida
        int[] v = new int[tamanho];
        for (int i = 0; i < tamanho; i++) {
            v[i] = Character.digit(cps[i], 10);
            if (v[i] < 0) return null;
        }
        return v;
    }

    public static boolean cpfValido(String digits) {
        int[] d = valores(digits, 11);
        if (d == null) return false;
        for (int n = 9; n <= 10; n++) {
            int soma = 0;
            for (int i = 0; i < n; i++) soma += d[i] * (n + 1 - i);
            int dv = (soma * 10) % 11;
            if (dv == 10) dv = 0;
            if (dv != d[n]) return false;
        }
        return true;
    }

    public static boolean cnpjValido(String digits) {
        int[] d = valores(digits, 14);
        if (d == null) return false;
        for (int n = 12; n <= 13; n++) {
            int[] pesos = n == 12 ? PESOS1 : PESOS2;
            int soma = 0;
            for (int i = 0; i < n; i++) soma += d[i] * pesos[i];
            int r = soma % 11;
            if ((r < 2 ? 0 : 11 - r) != d[n]) return false;
        }
        return true;
    }
}
