package me.clama.api.freemium.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaLimitOffset;
import me.clama.api.comum.api.ParametroInvalido;
import me.clama.api.comum.api.ParametrosDeQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FreemiumConfirmationTokenAdmin (freemium/admin.py) no mapa 7 §3 (D12): so leitura. O token e credencial (confirma o
 * pedido de outra pessoa), entao so sai o prefixo de 8 + "…"; ip_origem e device_hash ficam de fora; sem delete.
 */
@RestController
class TokensAdminController {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    TokensAdminController(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    record Item(UUID id, String token_curto, UUID pedido_id, String created_at, String expires_at, String used_at) {}

    private static final RowMapper<Item> ITEM = (rs, n) -> new Item(rs.getObject("id", UUID.class),
            rs.getString("token").substring(0, Math.min(8, rs.getString("token").length())) + "…",
            rs.getObject("pedido_id", UUID.class), DatasDrf.dataHora(rs.getObject("created_at", OffsetDateTime.class)),
            DatasDrf.dataHora(rs.getObject("expires_at", OffsetDateTime.class)),
            DatasDrf.dataHora(rs.getObject("used_at", OffsetDateTime.class)));

    @GetMapping("/api/admin/freemium/tokens/")
    Map<String, Object> listar(HttpServletRequest req) {
        PaginaLimitOffset pagina = PaginaLimitOffset.de(req);
        String p = ParametrosDeQuery.ultimo(req, "pedido");
        UUID pedido = null;
        if (p != null && !p.isEmpty()) {
            try {
                pedido = UUID.fromString(p);
            } catch (IllegalArgumentException e) {
                throw new ParametroInvalido("pedido");
            }
        }
        Object[] filtro = pedido == null ? new Object[0] : new Object[] {pedido};
        String where = pedido == null ? "" : " where pedido_id = ?";
        Object[] args = java.util.stream.Stream.concat(java.util.Arrays.stream(filtro),
                java.util.stream.Stream.of(pagina.limit(), pagina.offset())).toArray();
        return tx.execute(st -> pagina.resposta(
                jdbc.queryForObject("select count(*) from freemium_freemiumconfirmationtoken" + where, Long.class,
                        filtro),
                jdbc.query("select id, token, pedido_id, created_at, expires_at, used_at from "
                        + "freemium_freemiumconfirmationtoken" + where + " order by created_at desc, id limit ? "
                        + "offset ?", ITEM, args)));
    }
}
