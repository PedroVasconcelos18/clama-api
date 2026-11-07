package me.clama.api.comum.api;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import me.clama.api.comum.erro.NaoEncontrado;

/**
 * PageNumberPagination do DRF (page_size 20, max 100, page_size_query_param): page_size invalido ou <= 0 vira 20;
 * page ausente e 1, "last" e a ultima, e 0, nao inteiro ou alem do fim e 404 "Página inválida." (lista vazia na
 * pagina 1 e valida). next/previous sao replace_query_param/remove_query_param sobre a URL pedida: so o page muda,
 * parametros em ordem alfabetica; na pagina 2 o previous remove o page. Host e https como em {@link PaginaLimitOffset}.
 */
public final class PaginaPorPagina {
    private final long pagina;
    private final long ultima;
    private final int limit;
    private final long count;
    private final String base;
    private final Map<String, List<String>> query;

    private PaginaPorPagina(long pagina, long ultima, int limit, long count, String base,
            Map<String, List<String>> query) {
        this.pagina = pagina;
        this.ultima = ultima;
        this.limit = limit;
        this.count = count;
        this.base = base;
        this.query = query;
    }

    /** @throws NaoEncontrado 404 {"detail":"Página inválida."} */
    public static PaginaPorPagina de(HttpServletRequest req, long count) {
        BigInteger tamanho = PaginaLimitOffset.inteiro(ParametrosDeQuery.ultimo(req, "page_size"));
        int limit = tamanho == null || tamanho.signum() <= 0 ? PaginaLimitOffset.PADRAO
                : tamanho.min(BigInteger.valueOf(PaginaLimitOffset.MAXIMO)).intValue();
        long ultima = Math.max(1, (count + limit - 1) / limit);
        String bruto = ParametrosDeQuery.ultimo(req, "page");
        long pagina;
        if (bruto == null) {
            pagina = 1;
        } else if (bruto.equals("last")) {
            pagina = ultima;
        } else {
            BigInteger n = PaginaLimitOffset.inteiro(bruto);
            if (n == null || n.signum() <= 0 || n.compareTo(BigInteger.valueOf(ultima)) > 0) {
                throw NaoEncontrado.paginaInvalida();
            }
            pagina = n.longValueExact();
        }
        String https = PaginaLimitOffset.https(req.getHeader("X-Forwarded-Proto")) ? "https" : req.getScheme();
        return new PaginaPorPagina(pagina, ultima, limit, count, https + "://" + req.getHeader("Host")
                + req.getRequestURI(), PaginaLimitOffset.parseQs(req.getQueryString()));
    }

    public long offset() {
        return (pagina - 1) * limit;
    }

    public int limit() {
        return limit;
    }

    /** {count, next, previous, results}, nessa ordem. */
    public Map<String, Object> resposta(List<?> results) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", count);
        r.put("next", pagina < ultima ? link(pagina + 1) : null);
        r.put("previous", pagina > 1 ? link(pagina - 1 == 1 ? null : pagina - 1) : null);
        r.put("results", results);
        return r;
    }

    /** page null = remove_query_param. */
    private String link(Long novaPagina) {
        Map<String, List<String>> q = new TreeMap<>(query);
        if (novaPagina == null) {
            q.remove("page");
        } else {
            q.put("page", List.of(String.valueOf(novaPagina)));
        }
        List<String> pares = new ArrayList<>();
        q.forEach((k, vs) -> vs.forEach(v -> pares.add(PaginaLimitOffset.codifica(k) + "=" + PaginaLimitOffset.codifica(v))));
        return pares.isEmpty() ? base : base + "?" + String.join("&", pares);
    }
}
