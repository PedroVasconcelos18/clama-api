package me.clama.api.comum.api;

import jakarta.servlet.http.HttpServletRequest;

/** request.query_params.get do DRF: com parametro repetido, vale o ULTIMO valor. */
public final class ParametrosDeQuery {
    private ParametrosDeQuery() {}

    /** Ultimo valor de {@code nome}, ou null se ausente. */
    public static String ultimo(HttpServletRequest req, String nome) {
        String[] vs = req.getParameterValues(nome);
        return vs == null || vs.length == 0 ? null : vs[vs.length - 1];
    }
}
