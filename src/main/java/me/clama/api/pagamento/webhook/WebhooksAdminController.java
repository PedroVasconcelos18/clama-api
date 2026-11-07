package me.clama.api.pagamento.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import me.clama.api.blog.webhook.JobSincronizarEspelho;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaLimitOffset;
import me.clama.api.comum.api.ParametrosDeQuery;
import me.clama.api.comum.auditoria.RegistroDeAuditoria;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.identidade.Usuario;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WebhookEventoAdmin (payments/admin.py) no mapa 7 §2.3: lista sem payload e sem nome do pedido (o payload pode ter
 * PII do pagador; ZAPI legado tem telefone), detalhe com o payload, e reprocessar (D9) so de RECEBIDO/ERRO: WordPress
 * reenfileira o sincronizar-espelho, Mercado Pago passa pelo {@link ProcessamentoDePagamento} (o mesmo do webhook, com
 * o pedido travado), ASAAS/ZAPI nunca. Sem edicao nem exclusao (D10). Acesso admin vem do SegurancaConfig.
 */
@RestController
class WebhooksAdminController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ITEM = "/api/admin/webhooks/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}}/";
    private static final String COLUNAS = "id, created_at, updated_at, provider, event_type, status, external_event_id,"
            + " pedido_id, error_message";
    /** change_message do Django para o reprocesso (as chaves completas: o historico do /admin da KeyError sem elas). */
    private static final Object MUDOU_STATUS = List.of(Map.of("changed", Map.of("fields", List.of("status"))));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebhookEventoRepository eventos;
    private final ProcessamentoDePagamento processamento;
    private final Fila fila;
    private final JobSincronizarEspelho sincronizar;
    private final RegistroDeAuditoria auditoria;

    WebhooksAdminController(JdbcTemplate jdbc, TransactionTemplate tx, WebhookEventoRepository eventos,
            ProcessamentoDePagamento processamento, Fila fila, JobSincronizarEspelho sincronizar,
            RegistroDeAuditoria auditoria) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.eventos = eventos;
        this.processamento = processamento;
        this.fila = fila;
        this.sincronizar = sincronizar;
        this.auditoria = auditoria;
    }

    /** payload so no detalhe (null na lista, e a chave some). */
    record Item(UUID id, String created_at, String updated_at, String provider, String event_type, String status,
            String external_event_id, UUID pedido_id, String error_message,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode payload) {}

    private static RowMapper<Item> item(boolean comPayload) {
        return (rs, n) -> new Item(rs.getObject("id", UUID.class),
                DatasDrf.dataHora(rs.getObject("created_at", OffsetDateTime.class)),
                DatasDrf.dataHora(rs.getObject("updated_at", OffsetDateTime.class)), rs.getString("provider"),
                rs.getString("event_type"), rs.getString("status"), rs.getString("external_event_id"),
                rs.getObject("pedido_id", UUID.class), rs.getString("error_message"),
                comPayload ? JSON.readTree(rs.getString("payload")) : null);
    }

    @GetMapping("/api/admin/webhooks/")
    Map<String, Object> listar(HttpServletRequest req) {
        PaginaLimitOffset pagina = PaginaLimitOffset.de(req);
        List<String> onde = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (String campo : List.of("provider", "status", "event_type")) {
            String v = ParametrosDeQuery.ultimo(req, campo);
            if (v != null && !v.isEmpty()) {
                onde.add(campo + " = ?");
                args.add(v);
            }
        }
        String q = TextoPython.strip(Objects.toString(ParametrosDeQuery.ultimo(req, "q"), ""));
        if (!q.isEmpty()) {
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            onde.add("(external_event_id ilike ? or pedido_id::text ilike ?)");
            args.add(like);
            args.add(like);
        }
        String where = onde.isEmpty() ? "" : " where " + String.join(" and ", onde);
        return tx.execute(st -> {
            Long count = jdbc.queryForObject("select count(*) from payments_webhookevento" + where, Long.class,
                    args.toArray());
            List<Object> argsPagina = new ArrayList<>(args);
            argsPagina.add(pagina.limit());
            argsPagina.add(pagina.offset());
            return pagina.resposta(count, jdbc.query("select " + COLUNAS + " from payments_webhookevento" + where
                    + " order by created_at desc, id limit ? offset ?", item(false), argsPagina.toArray()));
        });
    }

    @GetMapping(ITEM)
    Item detalhe(@PathVariable UUID id) {
        return jdbc.query("select " + COLUNAS + ", payload::text as payload from payments_webhookevento where id = ?",
                item(true), id).stream().findFirst().orElseThrow(WebhooksAdminController::naoEncontrado);
    }

    @PostMapping(ITEM + "reprocessar/")
    ResponseEntity<Map<String, String>> reprocessar(@PathVariable UUID id, @AuthenticationPrincipal Usuario eu) {
        Item e = detalhe(id);
        if (new WebhookEvento(id, e.status()).terminal()) {
            throw new PastoralException(409, "webhook_ja_processado", "Webhook event already processed",
                    "Esse evento já foi processado.");
        }
        // relê o status dentro da transação da escrita: so grava "Changed status" se mudou (ERRO->ERRO nao mudou)
        Runnable auditar = () -> auditoria.registrar(eu.getId(), "payments", "webhookevento", id.toString(),
                e.provider() + ":" + e.external_event_id(), RegistroDeAuditoria.MUDANCA,
                e.status().equals(jdbc.queryForObject("select status from payments_webhookevento where id = ?",
                        String.class, id)) ? List.of() : MUDOU_STATUS);
        return switch (e.provider()) {
            case WebhookEventoRepository.WORDPRESS -> {
                // o job rele o payload gravado e pula linha terminal; instancia ja na fila = no-op
                boolean novo = Boolean.TRUE.equals(tx.execute(s -> {
                    auditar.run();
                    return fila.enfileirar(sincronizar, JobSincronizarEspelho.instancia(id), id);
                }));
                yield ResponseEntity.status(202).body(Map.of("status", novo ? "enfileirado" : "ja_na_fila"));
            }
            case WebhookEventoRepository.MERCADO_PAGO -> ResponseEntity.ok(Map.of("status", mercadoPago(e, auditar)));
            default -> throw new PastoralException(409, "webhook_sem_reprocesso", "Webhook provider not reprocessable",
                    "Eventos desse provedor não são reprocessados.");
        };
    }

    /** O dataId vem de payload.data.id (o do corpo; o estado vem do GET ao MP, a fonte de verdade: mapa §2.2). */
    private String mercadoPago(Item e, Runnable auditar) {
        JsonNode v = e.payload().path("data").path("id");
        String dataId = v.isString() ? v.asString() : v.isNumber() ? v.toString() : "";
        if (!e.event_type().equals("payment") || dataId.isEmpty()) {
            // o webhook marcaria IGNORADO antes do GET; so sobra RECEBIDO assim se ele caiu no meio
            tx.executeWithoutResult(s -> {
                eventos.marcarIgnorado(e.id(), !e.event_type().equals("payment")
                        ? "tipo não-payment: " + e.event_type() : "data.id ausente no payload");
                auditar.run();
            });
            return ProcessamentoDePagamento.Resultado.IGNORADO.status;
        }
        ProcessamentoDePagamento.Resultado r = processamento.processar(e.id(), e.external_event_id(), dataId, auditar);
        return switch (r) {
            case FALHA_NO_FETCH -> throw new PastoralException(502, "mercadopago_indisponivel",
                    "Mercado Pago payment fetch failed", "Não conseguimos consultar o Mercado Pago agora. O evento "
                            + "continua com erro; tente de novo em alguns minutos.");
            case FALHA_INTERNA -> throw new PastoralException(500, "reprocesso_falhou", "Processing failed",
                    "O processamento falhou e o evento continua com erro. A mensagem está no detalhe do evento.");
            default -> r.status;
        };
    }

    private static PastoralException naoEncontrado() {
        return new PastoralException(404, "not_found", "Webhook event not found", "Esse evento não foi encontrado.");
    }
}
