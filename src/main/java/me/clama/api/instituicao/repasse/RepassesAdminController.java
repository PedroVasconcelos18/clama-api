package me.clama.api.instituicao.repasse;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaLimitOffset;
import me.clama.api.comum.api.ParametroInvalido;
import me.clama.api.comum.api.ParametrosDeQuery;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.texto.TextoPython;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * RepasseAdmin (instituicoes/admin.py) so leitura (D13): lista e detalhe em SQL puro com join na instituicao.
 * Sem PII: o pedido aparece so como id, sem tocar em orders_pedido. Acesso admin vem do SegurancaConfig.
 */
@RestController
class RepassesAdminController {
    private static final Pattern UUID_HEX = Pattern.compile(
            "[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final String SELECT = """
            select r.id, r.pedido_id, r.instituicao_id, i.nome, r.valor_base_centavos, r.percentual, r.valor_centavos,
                   r.status, r.transferido_em, r.comprovante, r.created_at, r.updated_at
            from instituicoes_repasse r join instituicoes_instituicao i on i.id = r.instituicao_id""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    RepassesAdminController(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    record Item(UUID id, UUID pedido_id, UUID instituicao_id, String instituicao_nome, int valor_base_centavos,
            int percentual, int valor_centavos, String status, String transferido_em, String comprovante,
            String created_at, String updated_at) {}

    private static final RowMapper<Item> ITEM = (rs, n) -> new Item(rs.getObject("id", UUID.class),
            rs.getObject("pedido_id", UUID.class), rs.getObject("instituicao_id", UUID.class), rs.getString("nome"),
            rs.getInt("valor_base_centavos"), rs.getInt("percentual"), rs.getInt("valor_centavos"),
            rs.getString("status"), DatasDrf.dataHora(rs.getObject("transferido_em", OffsetDateTime.class)),
            rs.getString("comprovante"), DatasDrf.dataHora(rs.getObject("created_at", OffsetDateTime.class)),
            DatasDrf.dataHora(rs.getObject("updated_at", OffsetDateTime.class)));

    @GetMapping("/api/admin/repasses/")
    Map<String, Object> listar(HttpServletRequest req) {
        PaginaLimitOffset pagina = PaginaLimitOffset.de(req);
        List<String> onde = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        String status = ParametrosDeQuery.ultimo(req, "status");
        // valor desconhecido e ignorado, como o list_filter do Django
        if ("registrado".equals(status) || "transferido".equals(status)) {
            onde.add("r.status = ?");
            args.add(status);
        }
        String inst = ParametrosDeQuery.ultimo(req, "instituicao");
        if (inst != null && !inst.isEmpty()) {
            if (!UUID_HEX.matcher(inst).matches()) {
                throw new ParametroInvalido("instituicao");
            }
            onde.add("r.instituicao_id = ?::uuid");
            args.add(inst);
        }
        String q = TextoPython.strip(java.util.Objects.toString(ParametrosDeQuery.ultimo(req, "q"), ""));
        if (!q.isEmpty()) {
            onde.add("i.nome ilike ?");
            args.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String de = " from instituicoes_repasse r join instituicoes_instituicao i on i.id = r.instituicao_id"
                + (onde.isEmpty() ? "" : " where " + String.join(" and ", onde));

        return tx.execute(st -> {
            Long count = jdbc.queryForObject("select count(*)" + de, Long.class, args.toArray());
            List<Object> argsPagina = new ArrayList<>(args);
            argsPagina.add(pagina.limit());
            argsPagina.add(pagina.offset());
            String sql = SELECT + (onde.isEmpty() ? "" : " where " + String.join(" and ", onde))
                    + " order by r.created_at desc, r.id limit ? offset ?";
            return pagina.resposta(count, jdbc.query(sql, ITEM, argsPagina.toArray()));
        });
    }

    @GetMapping("/api/admin/repasses/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/")
    Item detalhe(@PathVariable UUID id) {
        return tx.execute(st -> jdbc.query(SELECT + " where r.id = ?", ITEM, id).stream().findFirst()
                .orElseThrow(() -> new PastoralException(404, "not_found", "Repasse não encontrado",
                        "Esse repasse não foi encontrado.")));
    }
}
