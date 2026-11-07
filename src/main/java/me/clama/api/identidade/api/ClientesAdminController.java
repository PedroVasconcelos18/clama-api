package me.clama.api.identidade.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaLimitOffset;
import me.clama.api.comum.api.ParametrosDeQuery;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminCustomerListView e AdminCustomerDetailView (admin_customer_views.py): acesso admin e do SegurancaConfig. As
 * tabelas do blog sao lidas em SQL puro (sem entidade). cpf_cnpj e telefone so saem decifrados no detalhe (entidade).
 */
@RestController
class ClientesAdminController {
    private static final String CONTAGENS = """
            (select count(*) from orders_pedido o where o.user_id = u.id) as total_pedidos,
            (select count(*) from orders_pedido o where o.user_id = u.id and not o.eh_gratuito) as pedidos_pagos,
            (select count(*) from orders_pedido o where o.user_id = u.id and o.eh_gratuito) as pedidos_gratuitos,
            (select count(*) from blog_comentario c where c.customer_id = u.id) as total_comentarios""";
    /** Ban ativo mais recente (defensivo: a constraint nao impede varios). */
    private static final String BAN = """
            left join lateral (select b.motivo, b.banido_em from blog_customerbanido b
                where b.customer_id = u.id and b.revogado_em is null order by b.banido_em desc limit 1) ban on true""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final UsuarioRepository usuarios;

    ClientesAdminController(JdbcTemplate jdbc, TransactionTemplate tx, UsuarioRepository usuarios) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.usuarios = usuarios;
    }

    record Item(long id, String email, String nome_completo, String date_joined, String freemium_used_at,
            boolean is_clama_admin, long total_pedidos, long pedidos_pagos, long pedidos_gratuitos,
            long total_comentarios, boolean is_banned, String motivo_ban, String banido_em) {}

    record Banimento(UUID id, String motivo, String banido_em, String banido_por_email, String revogado_em,
            String revogado_por_email) {}

    record Detalhe(long id, String email, String nome_completo, String date_joined, String last_login,
            boolean is_active, boolean is_clama_admin, String cpf_cnpj, String telefone, String nome_format_blog,
            String freemium_used_at, long total_pedidos, long pedidos_pagos, long pedidos_gratuitos,
            long total_comentarios, boolean is_banned, List<Banimento> banimentos) {}

    /** query_params.get: o ultimo valor. */
    private static String param(HttpServletRequest req, String nome) {
        return java.util.Objects.toString(ParametrosDeQuery.ultimo(req, nome), "");
    }

    @GetMapping("/api/admin/customers/")
    Map<String, Object> listar(HttpServletRequest req) {
        PaginaLimitOffset pagina = PaginaLimitOffset.de(req);
        List<String> onde = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        String q = TextoPython.strip(param(req, "q"));
        if (!q.isEmpty()) {
            onde.add("(u.email ilike ? or u.nome_completo ilike ?)");
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        switch (param(req, "banned")) {
            case "true" -> onde.add("ban.motivo is not null");
            case "false" -> onde.add("ban.motivo is null");
            default -> { }
        }
        switch (param(req, "freemium")) {
            case "true" -> onde.add("u.freemium_used_at is not null");
            case "false" -> onde.add("u.freemium_used_at is null");
            default -> { }
        }
        String de = " from users_user u " + BAN + (onde.isEmpty() ? "" : " where " + String.join(" and ", onde));

        return tx.execute(st -> {
            Long count = jdbc.queryForObject("select count(*)" + de, Long.class, args.toArray());
            List<Object> argsPagina = new ArrayList<>(args);
            argsPagina.add(pagina.limit());
            argsPagina.add(pagina.offset());
            List<Item> itens = jdbc.query("""
                    select u.id, u.email, u.nome_completo, u.date_joined, u.freemium_used_at, u.is_clama_admin,
                    """ + CONTAGENS + ", ban.motivo, ban.banido_em" + de
                    + " order by u.date_joined desc, u.id desc limit ? offset ?",
                    (rs, n) -> {
                        String motivo = rs.getString("motivo");
                        return new Item(rs.getLong("id"), rs.getString("email"), rs.getString("nome_completo"),
                                data(rs, "date_joined"), data(rs, "freemium_used_at"), rs.getBoolean("is_clama_admin"),
                                rs.getLong("total_pedidos"), rs.getLong("pedidos_pagos"),
                                rs.getLong("pedidos_gratuitos"), rs.getLong("total_comentarios"), motivo != null,
                                motivo, data(rs, "banido_em"));
                    }, argsPagina.toArray());
            return pagina.resposta(count, itens);
        });
    }

    private static String data(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        return DatasDrf.dataHora(rs.getObject(coluna, java.time.OffsetDateTime.class));
    }

    @GetMapping("/api/admin/customers/{id:[0-9]+}/")
    Detalhe detalhe(@PathVariable String id) {
        // o get_object do Django captura qualquer excecao (inclusive id fora do bigint) e vira 404
        long n;
        try {
            n = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw naoEncontrado();
        }
        return tx.execute(st -> {
            Usuario u = usuarios.findById(n).orElseThrow(ClientesAdminController::naoEncontrado);
            long[] c = jdbc.queryForObject("select " + CONTAGENS + " from users_user u where u.id = ?",
                    (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, n);
            List<Banimento> bans = jdbc.query("""
                    select b.id, b.motivo, b.banido_em, p.email as por, b.revogado_em, r.email as revogado_por
                    from blog_customerbanido b join users_user p on p.id = b.banido_por_id
                    left join users_user r on r.id = b.revogado_por_id
                    where b.customer_id = ? order by b.banido_em desc""",
                    (rs, i) -> new Banimento(rs.getObject("id", UUID.class), rs.getString("motivo"),
                            data(rs, "banido_em"), rs.getString("por"), data(rs, "revogado_em"),
                            rs.getString("revogado_por")), n);
            boolean banido = bans.stream().anyMatch(b -> b.revogado_em() == null);
            return new Detalhe(u.getId(), u.getEmail(), u.getNomeCompleto(), DatasDrf.dataHora(u.getDateJoined()),
                    DatasDrf.dataHora(u.getLastLogin()), u.isActive(), u.isClamaAdmin(), u.getCpfCnpj(),
                    u.getTelefone(), u.getNomeFormatBlog(), DatasDrf.dataHora(u.getFreemiumUsedAt()), c[0], c[1], c[2],
                    c[3], banido, bans);
        });
    }

    private static PastoralException naoEncontrado() {
        return new PastoralException(404, "not_found", "Customer nao encontrado",
                "Esse customer nao foi encontrado.");
    }
}
