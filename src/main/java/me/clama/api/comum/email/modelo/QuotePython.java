package me.clama.api.comum.email.modelo;

import java.nio.charset.StandardCharsets;

/** urllib.parse.quote(s) com safe='/': UTF-8, percent-encoding maiusculo, espaco vira %20. */
final class QuotePython {
    private QuotePython() {}

    static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~' || c == '/') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }
}
