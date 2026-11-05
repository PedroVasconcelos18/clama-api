package me.clama.api.comum.texto;

/** Equivalentes de str.strip() e str.isdigit() do Python (o Django depende deles). */
public final class TextoPython {
    private TextoPython() {}

    // str.isspace() do Python (strip() sem argumento)
    private static boolean pySpace(int c) {
        return (c >= 9 && c <= 13) || (c >= 0x1c && c <= 0x20) || c == 0x85 || c == 0xa0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f
                || c == 0x3000;
    }

    public static String strip(String v) {
        int i = 0;
        int f = v.length();
        while (i < f && pySpace(v.charAt(i))) {
            i++;
        }
        while (f > i && pySpace(v.charAt(f - 1))) {
            f--;
        }
        return v.substring(i, f);
    }

    // Numeric_Type=Digit fora de Nd, gerado do str.isdigit() do Python 3.14 (pares inicio,fim)
    private static final int[] DIGITOS_EXTRA = {
        0xb2, 0xb3, 0xb9, 0xb9, 0x1369, 0x1371, 0x19da, 0x19da, 0x2070, 0x2070, 0x2074, 0x2079,
        0x2080, 0x2089, 0x2460, 0x2468, 0x2474, 0x247c, 0x2488, 0x2490, 0x24ea, 0x24ea, 0x24f5, 0x24fd,
        0x24ff, 0x24ff, 0x2776, 0x277e, 0x2780, 0x2788, 0x278a, 0x2792, 0x10a40, 0x10a43, 0x10e60, 0x10e68,
        0x11052, 0x1105a, 0x1f100, 0x1f10a};

    // equivalente a str.isdigit() do Python
    public static boolean isDigit(int c) {
        if (Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER) {
            return true;
        }
        for (int k = 0; k < DIGITOS_EXTRA.length; k += 2) {
            if (c >= DIGITOS_EXTRA[k] && c <= DIGITOS_EXTRA[k + 1]) {
                return true;
            }
        }
        return false;
    }

}
