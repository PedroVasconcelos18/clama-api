package me.clama.api.comum.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import me.clama.api.comum.texto.TextoPython;

/** get_ident do DRF (NUM_PROXIES=None): o X-Forwarded-For inteiro sem espacos, senao REMOTE_ADDR. */
public final class ClienteIp {
    private ClienteIp() {}

    public static String de(HttpServletRequest req) {
        // o servidor WSGI junta headers repetidos com ","
        String xff = String.join(",", Collections.list(req.getHeaders("X-Forwarded-For")));
        if (xff.isEmpty()) {
            return req.getRemoteAddr();
        }
        StringBuilder sb = new StringBuilder();
        xff.codePoints().filter(c -> !TextoPython.isSpace(c)).forEach(sb::appendCodePoint);
        return sb.toString();
    }
}
