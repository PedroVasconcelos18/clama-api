package me.clama.api.blog.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import me.clama.api.blog.Comentario;
import me.clama.api.blog.ComentarioRepository;
import me.clama.api.blog.NomePublico;
import me.clama.api.blog.PostEspelho;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaPorPagina;
import me.clama.api.comum.api.ParametrosDeQuery;
import me.clama.api.comum.erro.NaoEncontrado;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminCommentsViewSet (views.py:424-443), so /api/blog/admin/** com hasAuthority(ADMIN) no SegurancaConfig. GET
 * (lista e item), DELETE; o resto e o 405 do handler. ?post= filtra pela FK legada post_id, que o R1 deixa sempre nula:
 * qualquer valor nao vazio nao casa nada. ?post_espelho=<slug> (D14, so Java) filtra pelo slug do espelho.
 */
@RestController
class ComentariosAdminController {
    static final String ROTA = "/api/blog/admin/comments/";
    private static final String ITEM = ROTA + "{id:[0-9a-fA-F-]{36}}/";

    private final ComentarioRepository comentarios;
    private final TransactionTemplate tx;

    ComentariosAdminController(ComentarioRepository comentarios, TransactionTemplate tx) {
        this.comentarios = comentarios;
        this.tx = tx;
    }

    /**
     * AdminComentarioSerializer. post e o UUID legado (sempre null, R1); post_slug e post_titulo somem com post nulo
     * (SkipField do DRF), entao nem existem aqui. post_espelho_* (D14) sao aditivas, depois de updated_at, e so existem
     * no Java; null se o espelho faltasse (o CHECK do banco impede).
     */
    record Item(UUID id, UUID post, Long customer, String customer_email, String customer_nome, String conteudo,
            boolean is_suspeito, String ip_address, String created_at, String updated_at,
            String post_espelho_slug, String post_espelho_titulo, String post_espelho_url) {
        static Item de(Comentario c) {
            PostEspelho e = c.getPostEspelho();
            return new Item(c.getId(), null, c.getCustomer().getId(), c.getCustomer().getEmail(),
                    NomePublico.de(c.getCustomer()), c.getConteudo(), c.isSuspeito(), c.getIpAddress(),
                    DatasDrf.dataHora(c.getCreatedAt()), DatasDrf.dataHora(c.getUpdatedAt()),
                    e == null ? null : e.getSlug(), e == null ? null : e.getTitulo(), e == null ? null : e.getUrl());
        }
    }

    @GetMapping(ROTA)
    Map<String, Object> listar(HttpServletRequest req) {
        boolean suspeitos = "suspeitos".equals(ParametrosDeQuery.ultimo(req, "status"));
        String post = ParametrosDeQuery.ultimo(req, "post");
        boolean legado = post != null && !post.isEmpty(); // post__slug: so a FK legada, que nunca casa (R1)
        String slug = Objects.requireNonNullElse(ParametrosDeQuery.ultimo(req, "post_espelho"), "");
        return tx.execute(s -> {
            PaginaPorPagina p = PaginaPorPagina.de(req, legado ? 0 : comentarios.contarDoAdmin(suspeitos, slug));
            return p.resposta(legado ? java.util.List.of() : comentarios
                    .doAdmin(suspeitos, slug, PageRequest.of((int) (p.offset() / p.limit()), p.limit())).stream()
                    .map(Item::de).toList());
        });
    }

    @GetMapping(ITEM)
    Item ver(@PathVariable String id) {
        return tx.execute(s -> Item.de(carregar(id)));
    }

    @DeleteMapping(ITEM)
    ResponseEntity<Void> apagar(@PathVariable String id) {
        tx.executeWithoutResult(s -> comentarios.delete(carregar(id)));
        return ResponseEntity.noContent().build();
    }

    /** Lookup [0-9a-fA-F-]{36}: o UUID() do Python tira os tracos e pede 32 hex; senao o get_object_or_404 e 404. */
    private Comentario carregar(String id) {
        String hex = id.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) {
            throw new NaoEncontrado();
        }
        UUID uuid = new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16),
                Long.parseUnsignedLong(hex.substring(16), 16));
        return comentarios.findById(uuid).orElseThrow(() -> new NaoEncontrado("Comentario"));
    }
}
