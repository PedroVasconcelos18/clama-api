package me.clama.api.freemium.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaLimitOffset;
import me.clama.api.comum.auditoria.RegistroDeAuditoria;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.identidade.Usuario;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * FreemiumBlacklistAdmin (freemium/admin.py) no mapa 7 §3 (D11): lista com hashes truncados (12 + "…", como o Django,
 * nunca inteiros), busca por e-mail/CPF/telefone em claro via POST (o hash sai no servidor, com a normalizacao do gate,
 * e o claro nunca vai a URL nem a log) e desbloqueio com auditoria. Sem "adicionar" nem edicao de hash.
 */
@RestController
class BloqueiosAdminController {
    private static final String ITEM = "/api/admin/freemium/bloqueios/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{4}-[0-9a-f]{12}}/";
    private static final String COLUNAS = "id, created_at, cpf_hash, email_hash, coalesce(telefone_hash, '') <> '' as "
            + "tem_telefone, coalesce(device_hash, '') <> '' as tem_device, coalesce(ip_hash, '') <> '' as tem_ip";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HashIdentificador hash;
    private final RegistroDeAuditoria auditoria;

    BloqueiosAdminController(JdbcTemplate jdbc, TransactionTemplate tx, HashIdentificador hash,
            RegistroDeAuditoria auditoria) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.hash = hash;
        this.auditoria = auditoria;
    }

    record Item(UUID id, String created_at, String cpf_hash_curto, String email_hash_curto, boolean tem_telefone,
            boolean tem_device, boolean tem_ip) {}

    private static final RowMapper<Item> ITEM_ROW = (rs, n) -> new Item(rs.getObject("id", UUID.class),
            DatasDrf.dataHora(rs.getObject("created_at", OffsetDateTime.class)), curto(rs.getString("cpf_hash")),
            curto(rs.getString("email_hash")), rs.getBoolean("tem_telefone"), rs.getBoolean("tem_device"),
            rs.getBoolean("tem_ip"));

    /** f"{hash[:12]}…" */
    private static String curto(String h) {
        return h.substring(0, Math.min(12, h.length())) + "…";
    }

    @GetMapping("/api/admin/freemium/bloqueios/")
    Map<String, Object> listar(HttpServletRequest req) {
        PaginaLimitOffset pagina = PaginaLimitOffset.de(req);
        return tx.execute(st -> pagina.resposta(
                jdbc.queryForObject("select count(*) from freemium_freemiumblacklist", Long.class),
                jdbc.query("select " + COLUNAS + " from freemium_freemiumblacklist order by created_at desc, id "
                        + "limit ? offset ?", ITEM_ROW, pagina.limit(), pagina.offset())));
    }

    /** Hash vazio nunca entra na busca: linhas com telefone_hash "" casariam com qualquer telefone sem digitos. */
    @PostMapping("/api/admin/freemium/bloqueios/buscar/")
    List<Item> buscar(@RequestBody(required = false) JsonNode corpo) {
        CamposDrf c = new CamposDrf(corpo);
        String email = valor(c, "email");
        String cpf = valor(c, "cpf_cnpj");
        String telefone = valor(c, "telefone");
        List<String> onde = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (email != null && !HashIdentificador.normalizarEmail(email).isEmpty()) {
            onde.add("email_hash = ?");
            args.add(hash.hashEmail(email));
        }
        if (temDigito(cpf)) {
            onde.add("cpf_hash = ?");
            args.add(hash.hashCpfCnpj(cpf));
        }
        if (temDigito(telefone)) {
            onde.add("telefone_hash = ?");
            args.add(hash.hashTelefone(telefone));
        }
        if (onde.isEmpty()) {
            throw new CamposDrf.Invalidos(Map.of("non_field_errors", List.of("Informe e-mail, CPF/CNPJ ou telefone.")));
        }
        return jdbc.query("select " + COLUNAS + " from freemium_freemiumblacklist where " + String.join(" or ", onde)
                + " order by created_at desc, id", ITEM_ROW, args.toArray());
    }

    /** Texto aparado, ou null se ausente, nulo, vazio ou nao textual: tudo isso e "nao informado". */
    private static String valor(CamposDrf c, String campo) {
        JsonNode v = c.get(campo);
        if (v == null || !(v.isString() || v.isNumber())) {
            return null;
        }
        String s = TextoPython.strip(v.asString());
        return s.isEmpty() ? null : s;
    }

    private static boolean temDigito(String v) {
        return v != null && v.codePoints().anyMatch(TextoPython::isDigit);
    }

    @DeleteMapping(ITEM)
    ResponseEntity<Void> desbloquear(@PathVariable UUID id, @AuthenticationPrincipal Usuario eu) {
        tx.executeWithoutResult(st -> {
            if (jdbc.update("delete from freemium_freemiumblacklist where id = ?", id) == 0) {
                throw new PastoralException(404, "not_found", "Blacklist entry not found",
                        "Esse bloqueio não foi encontrado.");
            }
            // object_repr leva o id: o str() do Django nao existe e hash nem e-mail entram no historico
            auditoria.registrar(eu.getId(), "freemium", "freemiumblacklist", id.toString(), id.toString(),
                    RegistroDeAuditoria.EXCLUSAO, null);
        });
        return ResponseEntity.noContent().build();
    }
}
