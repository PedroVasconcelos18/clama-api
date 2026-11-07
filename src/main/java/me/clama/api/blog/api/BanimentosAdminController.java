package me.clama.api.blog.api;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.blog.CustomerBanido;
import me.clama.api.blog.CustomerBanidoRepository;
import me.clama.api.blog.EntidadeDoBlog;
import me.clama.api.blog.ErroPlano;
import me.clama.api.blog.NomePublico;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaPorPagina;
import me.clama.api.comum.erro.NaoEncontrado;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
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
 * AdminBannedCustomersViewSet (views.py:446-523): bans ativos, lookup customer_id (\d+). POST idempotente (200 com o
 * ban existente, 201 com o novo); a checagem e a criacao rodam sob pg_advisory_xact_lock por customer (R9), entao dois
 * POSTs simultaneos deixam um ban ativo so. DELETE revoga um ban por chamada (o mais recente).
 */
@RestController
class BanimentosAdminController {
    static final String ROTA = "/api/blog/admin/banned-customers/";
    private static final String ITEM = ROTA + "{customerId:\\d+}/";
    private static final String MOTIVO_CURTO = "Informe um motivo para o banimento (≥3 caracteres).";

    private final CustomerBanidoRepository banidos;
    private final UsuarioRepository usuarios;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock relogio;

    BanimentosAdminController(CustomerBanidoRepository banidos, UsuarioRepository usuarios, EntityManager em,
            TransactionTemplate tx, Clock relogio) {
        this.banidos = banidos;
        this.usuarios = usuarios;
        this.em = em;
        this.tx = tx;
        this.relogio = relogio;
    }

    /** CustomerBanidoListSerializer; revogado_em e sempre null (so ativos). */
    record Item(UUID id, Long customer, String customer_email, String customer_nome, String motivo,
            String banido_em, String banido_por_email, String revogado_em) {
        static Item de(CustomerBanido b) {
            return new Item(b.getId(), b.getCustomer().getId(), b.getCustomer().getEmail(),
                    NomePublico.de(b.getCustomer()), b.getMotivo(), DatasDrf.dataHora(b.getBanidoEm()),
                    b.getBanidoPor().getEmail(), DatasDrf.dataHora(b.getRevogadoEm()));
        }
    }

    @GetMapping(ROTA)
    Map<String, Object> listar(HttpServletRequest req) {
        return tx.execute(s -> {
            PaginaPorPagina p = PaginaPorPagina.de(req, banidos.countByRevogadoEmIsNull());
            return p.resposta(banidos.ativos(PageRequest.of((int) (p.offset() / p.limit()), p.limit())).stream()
                    .map(Item::de).toList());
        });
    }

    @GetMapping(ITEM)
    Item ver(@PathVariable String customerId) {
        return tx.execute(s -> Item.de(ativo(id(customerId)).orElseThrow(() -> new NaoEncontrado("CustomerBanido"))));
    }

    @PostMapping(ROTA)
    ResponseEntity<Item> banir(@RequestBody(required = false) JsonNode corpo, @AuthenticationPrincipal Usuario eu) {
        CamposDrf campos = new CamposDrf(corpo);
        // bigint: um id acima do int32 chega ao 404 customer_nao_encontrado, como no Django
        Long customerId = campos.longo("customer_id", true, Long.MIN_VALUE, Long.MAX_VALUE,
                "Certifque-se de que este valor seja maior ou igual a " + Long.MIN_VALUE + ".");
        String motivo = campos.texto("motivo",
                m -> m.codePointCount(0, m.length()) < 3 ? List.of(MOTIVO_CURTO) : List.of());
        campos.validar();
        return tx.execute(s -> {
            // R9: serializa por customer ate o commit; o lock vem antes de reler o alvo, entao uma promocao a admin
            // commitada no meio tambem e vista (e quem espera ja enxerga o ban de quem passou na frente)
            // ponytail: colisao de hashtext entre dois customers so serializa os bans deles, inofensivo
            em.createNativeQuery("select 1 from (select pg_advisory_xact_lock(hashtext('ban-blog:' || cast(?1 as text)))) x")
                    .setParameter(1, customerId).getSingleResult();
            Usuario alvo = usuarios.findById(customerId).orElseThrow(() -> new RespostaDeErro(404,
                    new ErroPlano("customer_nao_encontrado", "Customer não encontrado.")));
            if (alvo.isClamaAdmin()) {
                throw new RespostaDeErro(400, new ErroPlano("cannot_ban_admin", "Admins não podem ser banidos. Para "
                        + "remover acesso, edite a flag is_clama_admin diretamente."));
            }
            var existente = ativo(alvo.getId());
            if (existente.isPresent()) {
                return ResponseEntity.ok(Item.de(existente.get()));
            }
            CustomerBanido b = new CustomerBanido();
            b.setCustomer(alvo);
            b.setMotivo(motivo);
            b.setBanidoPor(em.getReference(Usuario.class, eu.getId()));
            return ResponseEntity.status(201).body(Item.de(banidos.saveAndFlush(b)));
        });
    }

    @DeleteMapping(ITEM)
    ResponseEntity<Void> revogar(@PathVariable String customerId, @AuthenticationPrincipal Usuario eu) {
        return tx.execute(s -> {
            var ban = ativo(id(customerId));
            if (ban.isEmpty()) {
                return ResponseEntity.notFound().build(); // Response(status=404): corpo vazio
            }
            ban.get().revogar(em.getReference(Usuario.class, eu.getId()), EntidadeDoBlog.agora(relogio));
            ban.get().marcarAlterada();
            banidos.saveAndFlush(ban.get());
            return ResponseEntity.noContent().build();
        });
    }

    private java.util.Optional<CustomerBanido> ativo(long customerId) {
        return banidos.findFirstByCustomer_IdAndRevogadoEmIsNullOrderByBanidoEmDescIdDesc(customerId);
    }

    /** \d+ maior que um bigint nao casa usuario nenhum. */
    private static long id(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
