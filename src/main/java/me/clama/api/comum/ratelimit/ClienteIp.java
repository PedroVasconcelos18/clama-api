package me.clama.api.comum.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.util.Collections;
import java.util.regex.Pattern;
import me.clama.api.comum.texto.TextoPython;

/** Os IPs do cliente que o Django usa: o do throttle (get_ident) e o do consentimento/Turnstile (1o item do XFF). */
public final class ClienteIp {
    private static final Pattern IPV4 =
            Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

    private ClienteIp() {}

    /** get_ident do DRF (NUM_PROXIES=None): o X-Forwarded-For inteiro sem espacos, senao REMOTE_ADDR. */
    public static String de(HttpServletRequest req) {
        String xff = xff(req);
        if (xff.isEmpty()) {
            return req.getRemoteAddr();
        }
        StringBuilder sb = new StringBuilder();
        xff.codePoints().filter(c -> !TextoPython.isSpace(c)).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /**
     * _ip_request das views de pedido: 1o item do X-Forwarded-For (strip), senao REMOTE_ADDR; null se nao for IPv4/IPv6.
     * Vale tambem para o consent_ip, que no Django nao e validado (lixo no XFF da 500 no cast para inet).
     */
    public static String validado(HttpServletRequest req) {
        String xff = xff(req);
        String ip = xff.isEmpty() ? req.getRemoteAddr() : TextoPython.strip(xff.split(",", -1)[0]);
        return ip != null && valido(ip) ? ip : null;
    }

    /**
     * _client_ip do blog (views.py:59-63): 1o item do X-Forwarded-For (strip), senao REMOTE_ADDR, senao "". Texto cru,
     * sem validar se e IP (o comentario grava o que vier, cifrado).
     */
    public static String cru(HttpServletRequest req) {
        String xff = xff(req);
        if (!xff.isEmpty()) {
            return TextoPython.strip(xff.split(",", -1)[0]);
        }
        return req.getRemoteAddr() == null ? "" : req.getRemoteAddr();
    }

    /**
     * validate_ipv46_address do Django: IPv4 sem zeros a esquerda; IPv6 do ipaddress (ate 39 caracteres).
     * ponytail: IPv6 com zona ("fe80::1%eth0") e recusado (o Python aceita, o inet do Postgres nao).
     */
    public static boolean valido(String ip) {
        if (IPV4.matcher(ip).matches()) {
            return true;
        }
        if (!ip.contains(":") || ip.length() > 39 || ip.contains("[") || ip.contains("%")) {
            return false;
        }
        int ultimo = ip.lastIndexOf(':');
        if (ip.indexOf('.', ultimo) >= 0 && !IPV4.matcher(ip.substring(ultimo + 1)).matches()) {
            return false; // IPv4 embutido segue a mesma regra
        }
        try {
            Inet6Address.ofLiteral(ip);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // o servidor WSGI junta headers repetidos com ","
    private static String xff(HttpServletRequest req) {
        return String.join(",", Collections.list(req.getHeaders("X-Forwarded-For")));
    }
}
