package me.clama.api.pedido.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import me.clama.api.comum.Dinheiro;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaLimitOffset;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminPedidoListView e AdminPedidoDetailView (admin_views.py): acesso admin e do SegurancaConfig (/api/admin/**).
 * Filtros em SQL puro (JdbcTemplate); os itens vem da entidade, que decifra a PII. Filtro malformado: o Django da 500,
 * aqui 400 {"detail": "Parâmetro <nome> inválido."}.
 */
@RestController
class PedidosAdminController {
    private static final Pattern UUID_HEX = Pattern.compile("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern INTEIRO = Pattern.compile("[+-]?[0-9]+");
    private static final Map<String, String> ORDENACOES = Map.of("created_at", "created_at", "-created_at",
            "created_at desc", "valor_centavos", "valor_centavos", "-valor_centavos", "valor_centavos desc", "status",
            "status", "-status", "status desc");

    private final PedidoRepository pedidos;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    PedidosAdminController(PedidoRepository pedidos, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.pedidos = pedidos;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    static class ParametroInvalido extends RuntimeException {
        ParametroInvalido(String nome) {
            super("Parâmetro " + nome + " inválido.", null, false, false);
        }
    }

    @ExceptionHandler(ParametroInvalido.class)
    org.springframework.http.ResponseEntity<Map<String, String>> invalido(ParametroInvalido e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }

    /** AdminPedidoListSerializer. */
    record Item(UUID id, String created_at, String nome, String email, String telefone, String plano_nome,
            int valor_centavos, String valor_reais_str, String status, String canal_entrega, boolean eh_gratuito) {
        static Item de(Pedido p) {
            return new Item(p.getId(), DatasDrf.dataHora(p.getCreatedAt()), p.getNome(), p.getEmail(), p.getTelefone(),
                    p.getPlano().getNome(), p.getValorCentavos(), p.valorReaisStr(), p.getStatus().valor(),
                    p.getCanalEntrega().valor(), p.isEhGratuito());
        }
    }

    record Plano(UUID id, String nome, String valor_reais_str) {}

    record Instituicao(UUID id, String nome, String logo) {}

    record Webhook(UUID id, String provider, String external_event_id, String event_type, String status,
            String error_message, String created_at) {}

    /** AdminPedidoDetailSerializer (sem payload dos webhooks, sem cpf/pix/consent/device_hash/user). */
    record Detalhe(UUID id, String created_at, String updated_at, String nome, String email, String telefone,
            Integer idade, String sexo, String pedido_oracao, String oracao_gerada, Plano plano,
            Instituicao instituicao, int valor_centavos, String valor_reais_str, boolean eh_gratuito, String status,
            String canal_entrega, String provider_payment_id, String provider_checkout_url,
            String whatsapp_message_id, String whatsapp_delivered_at, String whatsapp_read_at, int retry_count,
            String last_error, List<Webhook> webhook_events) {}

    @GetMapping("/api/admin/pedidos/")
    Map<String, Object> listar(HttpServletRequest req) {
        PaginaLimitOffset pagina = PaginaLimitOffset.de(req);
        List<String> onde = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        String status = req.getParameter("status");
        if (status != null && !status.isEmpty()) {
            List<String> valores = new ArrayList<>();
            for (String s : status.split(",", -1)) {
                valores.add(TextoPython.strip(s).toLowerCase(Locale.ROOT));
            }
            onde.add("status = any(?)");
            args.add(valores.toArray(String[]::new));
        }
        String canal = req.getParameter("canal");
        if (canal != null && !canal.isEmpty()) {
            onde.add("canal_entrega = ?");
            args.add(canal.toLowerCase(Locale.ROOT));
        }
        String plano = req.getParameter("plano");
        if (plano != null && !plano.isEmpty()) {
            if (!UUID_HEX.matcher(plano).matches()) {
                throw new ParametroInvalido("plano");
            }
            onde.add("plano_id = ?::uuid");
            args.add(plano);
        }
        dia(req, "created_after", ">=", onde, args);
        dia(req, "created_before", "<=", onde, args);
        String userId = req.getParameter("user_id");
        if (userId != null && !userId.isEmpty()) {
            String u = TextoPython.strip(userId);
            if (!INTEIRO.matcher(u).matches()) {
                throw new ParametroInvalido("user_id");
            }
            onde.add("user_id = ?::bigint");
            args.add(u.startsWith("+") ? u.substring(1) : u);
        }
        String q = req.getParameter("q");
        if (q != null && !q.isEmpty()) {
            // paridade com o bug do Django: o ILIKE roda no texto cifrado; a busca de verdade fica para a Etapa 7
            onde.add("(nome ilike ? or email ilike ?)");
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        String ordem = ORDENACOES.getOrDefault(req.getParameter("ordering") == null ? "" : req.getParameter("ordering"),
                "created_at desc");
        String filtro = onde.isEmpty() ? "" : " where " + String.join(" and ", onde);

        return tx.execute(st -> {
            Long count = jdbc.queryForObject("select count(*) from orders_pedido" + filtro, Long.class, args.toArray());
            List<Object> argsPagina = new ArrayList<>(args);
            argsPagina.add(pagina.limit());
            argsPagina.add(pagina.offset());
            List<UUID> ids = jdbc.queryForList("select id from orders_pedido" + filtro + " order by " + ordem
                    + ", created_at desc, id limit ? offset ?", UUID.class, argsPagina.toArray());
            Map<UUID, Pedido> porId = new java.util.HashMap<>();
            if (!ids.isEmpty()) {
                pedidos.comPlano(ids).forEach(p -> porId.put(p.getId(), p));
            }
            return pagina.resposta(count, ids.stream().map(porId::get).map(Item::de).toList());
        });
    }

    /** created_at__date no fuso America/Sao_Paulo; data invalida e 400. */
    private static void dia(HttpServletRequest req, String nome, String op, List<String> onde, List<Object> args) {
        String v = req.getParameter(nome);
        if (v == null || v.isEmpty()) {
            return;
        }
        try {
            args.add(java.sql.Date.valueOf(LocalDate.parse(v)));
        } catch (DateTimeParseException e) {
            throw new ParametroInvalido(nome);
        }
        onde.add("(created_at at time zone 'America/Sao_Paulo')::date " + op + " ?");
    }

    @GetMapping("/api/admin/pedidos/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/")
    Detalhe detalhe(@PathVariable UUID id) {
        return tx.execute(st -> {
            Pedido p = pedidos.detalhe(id).orElseThrow(() -> new PastoralException(404, "not_found",
                    "Pedido não encontrado", "Esse pedido não foi encontrado. Pode ter sido removido?"));
            var i = p.getInstituicao();
            // WebhookEvento so mapeia id e status: o resto vem em SQL puro, por pedido_id e sem o payload
            List<Webhook> eventos = jdbc.query("""
                    select id, provider, external_event_id, event_type, status, error_message, created_at
                    from payments_webhookevento where pedido_id = ? order by created_at desc""",
                    (rs, n) -> new Webhook(rs.getObject("id", UUID.class), rs.getString("provider"),
                            rs.getString("external_event_id"), rs.getString("event_type"), rs.getString("status"),
                            rs.getString("error_message"),
                            DatasDrf.dataHora(rs.getObject("created_at", java.time.OffsetDateTime.class))), id);
            return new Detalhe(p.getId(), DatasDrf.dataHora(p.getCreatedAt()), DatasDrf.dataHora(p.getUpdatedAt()),
                    p.getNome(), p.getEmail(), p.getTelefone(), p.getIdade(), p.getSexo(), p.getPedidoOracao(),
                    p.getOracaoGerada(),
                    new Plano(p.getPlano().getId(), p.getPlano().getNome(),
                            Dinheiro.centavosParaReaisStr(p.getPlano().getValorCentavos())),
                    i == null ? null : new Instituicao(i.getId(), i.getNome(), i.getLogo()), p.getValorCentavos(),
                    p.valorReaisStr(), p.isEhGratuito(), p.getStatus().valor(), p.getCanalEntrega().valor(),
                    p.getProviderPaymentId(), p.getProviderCheckoutUrl(), p.getWhatsappMessageId(),
                    DatasDrf.dataHora(p.getWhatsappDeliveredAt()), DatasDrf.dataHora(p.getWhatsappReadAt()),
                    p.getRetryCount(), p.getLastError(), eventos);
        });
    }
}
