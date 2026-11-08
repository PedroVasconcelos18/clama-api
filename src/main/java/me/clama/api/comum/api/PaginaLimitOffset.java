package me.clama.api.comum.api;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import me.clama.api.comum.texto.TextoPython;

/**
 * LimitOffsetPagination do DRF (default 20, max 100): limit invalido ou <= 0 vira o default; offset invalido ou
 * negativo vira 0. next/previous sao URLs absolutas montadas como replace_query_param/remove_query_param: host do
 * header Host, https com X-Forwarded-Proto: https (X-Forwarded-Host e ignorado), parametros em ordem alfabetica.
 */
public final class PaginaLimitOffset {
    public static final int PADRAO = 20;
    public static final int MAXIMO = 100;
    private static final Pattern INTEIRO = Pattern.compile("[+-]?[0-9]+");

    private final int limit;
    private final long offset;
    private final String base;
    private final Map<String, List<String>> query;

    private PaginaLimitOffset(int limit, long offset, String base, Map<String, List<String>> query) {
        this.limit = limit;
        this.offset = offset;
        this.base = base;
        this.query = query;
    }

    public static PaginaLimitOffset de(HttpServletRequest req) {
        Map<String, List<String>> query = parseQs(req.getQueryString());
        BigInteger limit = inteiro(ultimo(query, "limit"));
        BigInteger offset = inteiro(ultimo(query, "offset"));
        int l = limit == null || limit.signum() <= 0 ? PADRAO : limit.min(BigInteger.valueOf(MAXIMO)).intValue();
        // ponytail: offset acima de 2^62 e travado; o Django estoura no banco (500), aqui vira pagina vazia
        long o = offset == null || offset.signum() < 0 ? 0 : offset.min(BigInteger.ONE.shiftLeft(62)).longValue();
        String https = https(req.getHeader("X-Forwarded-Proto")) ? "https" : req.getScheme();
        return new PaginaLimitOffset(l, o, https + "://" + req.getHeader("Host") + req.getRequestURI(), query);
    }

    /** Primeiro valor da lista (proxies em cadeia), sem caixa. */
    public static boolean https(String proto) {
        return proto != null && proto.split(",", 2)[0].trim().equalsIgnoreCase("https");
    }

    public int limit() {
        return limit;
    }

    public long offset() {
        return offset;
    }

    /** {count, next, previous, results}, nessa ordem. */
    public Map<String, Object> resposta(long count, List<?> results) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", count);
        r.put("next", offset + limit >= count ? null : link(offset + limit));
        r.put("previous", offset <= 0 ? null : link(offset - limit <= 0 ? null : offset - limit));
        r.put("results", results);
        return r;
    }

    /** limit sempre vai; offset null = remove_query_param. */
    private String link(Long novoOffset) {
        Map<String, List<String>> q = new TreeMap<>(query);
        q.put("limit", List.of(String.valueOf(limit)));
        if (novoOffset == null) {
            q.remove("offset");
        } else {
            q.put("offset", List.of(String.valueOf(novoOffset)));
        }
        List<String> pares = new ArrayList<>();
        q.forEach((k, vs) -> vs.forEach(v -> pares.add(codifica(k) + "=" + codifica(v))));
        return base + "?" + String.join("&", pares);
    }

    /** request.query_params[chave]: o ultimo valor. */
    private static String ultimo(Map<String, List<String>> q, String chave) {
        List<String> vs = q.get(chave);
        return vs == null ? null : vs.getLast();
    }

    /** int() do Python (espacos e sinal +); invalido = null. */
    static BigInteger inteiro(String v) {
        if (v == null) {
            return null;
        }
        String s = TextoPython.strip(v);
        return INTEIRO.matcher(s).matches() ? new BigInteger(s.startsWith("+") ? s.substring(1) : s) : null;
    }

    /** parse_qs(keep_blank_values=True). */
    static Map<String, List<String>> parseQs(String qs) {
        Map<String, List<String>> q = new TreeMap<>();
        if (qs == null) {
            return q;
        }
        for (String par : qs.split("&")) {
            if (par.isEmpty()) {
                continue;
            }
            int i = par.indexOf('=');
            String k = decodifica(i < 0 ? par : par.substring(0, i));
            String v = i < 0 ? "" : decodifica(par.substring(i + 1));
            q.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return q;
    }

    private static String decodifica(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s; // %zz solto: o Python deixa como veio
        }
    }

    /** urlencode do Python (quote_plus): "*" vira %2A e "~" fica. */
    static String codifica(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("*", "%2A").replace("%7E", "~");
    }
}
