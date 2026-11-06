package me.clama.api.comum.texto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** Equivalentes de str.strip(), str.isdigit(), str() e repr() do Python (o Django depende deles). */
public final class TextoPython {
    private TextoPython() {}

    // str.isspace() do Python (strip() sem argumento)
    public static boolean isSpace(int c) {
        return (c >= 9 && c <= 13) || (c >= 0x1c && c <= 0x20) || c == 0x85 || c == 0xa0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f
                || c == 0x3000;
    }

    public static String strip(String v) {
        int i = 0;
        int f = v.length();
        while (i < f && isSpace(v.charAt(i))) {
            i++;
        }
        while (f > i && isSpace(v.charAt(f - 1))) {
            f--;
        }
        return v.substring(i, f);
    }

    /** str.split() sem argumento: separa por corridas de whitespace do Python e descarta vazios. */
    public static List<String> split(String v) {
        List<String> partes = new ArrayList<>();
        int ini = -1;
        for (int i = 0; i < v.length(); i++) {
            if (isSpace(v.charAt(i))) {
                if (ini >= 0) {
                    partes.add(v.substring(ini, i));
                    ini = -1;
                }
            } else if (ini < 0) {
                ini = i;
            }
        }
        if (ini >= 0) {
            partes.add(v.substring(ini));
        }
        return partes;
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

    /** str() do Python sobre o valor que o json.loads devolveria (string sai crua, o resto como repr). */
    public static String str(JsonNode v) {
        return v.isString() ? v.asString() : repr(v);
    }

    /** repr() do Python sobre o valor que o json.loads devolveria. */
    public static String repr(JsonNode v) {
        if (v.isNull()) {
            return "None";
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? "True" : "False";
        }
        if (v.isIntegralNumber()) {
            return v.bigIntegerValue().toString();
        }
        if (v.isNumber()) {
            return reprFloat(v.doubleValue());
        }
        if (v.isString()) {
            return reprStr(v.asString());
        }
        if (v.isArray()) {
            return v.valueStream().map(TextoPython::repr).collect(Collectors.joining(", ", "[", "]"));
        }
        return v.propertyStream().map(e -> reprStr(e.getKey()) + ": " + repr(e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    // ponytail: escapa so \\, aspas e controles ASCII; caracteres nao imprimiveis do Unicode saem crus (o Python escapa)
    static String reprStr(String s) {
        char aspa = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(aspa);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c == aspa) {
                        sb.append('\\').append(c);
                    } else if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append(aspa).toString();
    }

    /** repr(float): os digitos mais curtos (os mesmos do Double.toString) e notacao cientifica fora de [1e-4, 1e16). */
    static String reprFloat(double d) {
        BigDecimal b = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        int exp = b.precision() - b.scale() - 1;
        if (exp >= -4 && exp < 16) {
            String p = b.toPlainString();
            return p.contains(".") ? p : p + ".0";
        }
        String digitos = b.unscaledValue().abs().toString();
        String mantissa = digitos.length() == 1 ? digitos : digitos.charAt(0) + "." + digitos.substring(1);
        return (b.signum() < 0 ? "-" : "") + mantissa + "e" + (exp < 0 ? "-" : "+")
                + String.format(Locale.ROOT, "%02d", Math.abs(exp));
    }
}
