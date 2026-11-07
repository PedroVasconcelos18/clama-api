package me.clama.api.blog.api;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import me.clama.api.blog.Comentario;
import me.clama.api.blog.ComentarioRepository;
import me.clama.api.blog.ErroPlano;
import me.clama.api.blog.JanelaFixa;
import me.clama.api.blog.NomePublico;
import me.clama.api.blog.PostEspelho;
import me.clama.api.blog.PostNaoEncontrado;
import me.clama.api.blog.ResolucaoDoEspelho;
import me.clama.api.blog.wordpress.WordPressIndisponivel;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaPorPagina;
import me.clama.api.comum.erro.NaoEncontrado;
import me.clama.api.comum.ratelimit.ClienteIp;
import me.clama.api.identidade.Usuario;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ComentarioListCreateView (views.py:165-295). GET AllowAny (Cache-Control publico de 10 s em toda resposta, no
 * SegurancaConfig); POST com IsUnbannedCustomer (SegurancaConfig/SemBanimento), 5/min na janela fixa antes da
 * validacao. O espelho e resolvido fora da transacao de escrita (R10).
 */
@RestController
class ComentariosController {
    static final String ROTA = "/api/blog/posts/{slug:[-a-zA-Z0-9_]+}/comments/";
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String CURTO = "Esse comentário precisa de um pouquinho mais de texto pra ser publicado.";

    private final ResolucaoDoEspelho resolucao;
    private final ComentarioRepository comentarios;
    private final JanelaFixa janela;
    private final EntityManager em;
    private final TransactionTemplate tx;

    ComentariosController(ResolucaoDoEspelho resolucao, ComentarioRepository comentarios, JanelaFixa janela,
            EntityManager em, TransactionTemplate tx) {
        this.resolucao = resolucao;
        this.comentarios = comentarios;
        this.janela = janela;
        this.em = em;
        this.tx = tx;
    }

    /** ComentarioSerializer: post e o UUID legado (R1: sempre null), customer e o id do usuario. */
    record Item(UUID id, UUID post, Long customer, String customer_nome, String conteudo, String created_at,
            String updated_at) {
        static Item de(Comentario c) {
            return new Item(c.getId(), null, c.getCustomer().getId(), NomePublico.de(c.getCustomer()), c.getConteudo(),
                    DatasDrf.dataHora(c.getCreatedAt()), DatasDrf.dataHora(c.getUpdatedAt()));
        }
    }

    /**
     * CharField(max_length=2000) + validate_conteudo: lista para os erros do campo, dict dentro do campo para menos de 3
     * caracteres (depois do trim). obrigatorio=false (PATCH parcial): ausente devolve null.
     */
    static String conteudo(JsonNode corpo, boolean obrigatorio) {
        CamposDrf campos = new CamposDrf(corpo);
        String s = campos.texto("conteudo", obrigatorio, CamposDrf.BRANCO, CamposDrf.maxLength(2000));
        campos.validar();
        if (s != null && s.codePointCount(0, s.length()) < 3) {
            throw new RespostaDeErro(400, Map.of("conteudo", new ErroPlano("comentario_invalido", CURTO)));
        }
        return s;
    }

    /** O JSONParser do DRF: corpo vazio vale {} (CamposDrf), JSON invalido e o 400 "JSON parse error". */
    private static JsonNode json(String corpo) {
        if (corpo == null || corpo.isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(corpo);
        } catch (JacksonException e) {
            throw new HttpMessageNotReadableException("JSON invalido", e, null);
        }
    }

    @GetMapping(ROTA)
    Map<String, Object> listar(@PathVariable String slug, HttpServletRequest req) {
        UUID espelho;
        try {
            espelho = resolucao.resolver(slug).getId(); // 409 passa; o resto vira o Http404 da view
        } catch (PostNaoEncontrado | WordPressIndisponivel e) {
            throw new NaoEncontrado();
        }
        return tx.execute(s -> {
            PaginaPorPagina p = PaginaPorPagina.de(req, comentarios.countByPostEspelho_Id(espelho));
            return p.resposta(comentarios.doEspelho(espelho, PageRequest.of((int) (p.offset() / p.limit()), p.limit()))
                    .stream().map(Item::de).toList());
        });
    }

    @PostMapping(ROTA)
    ResponseEntity<Item> comentar(@PathVariable String slug, @AuthenticationPrincipal Usuario eu,
            @RequestBody(required = false) String corpo, HttpServletRequest req) {
        // o corpo so e lido depois de contar: JSON quebrado tambem gasta a janela e leva 429 (R11)
        if (janela.excedeu("comentario", eu.getId(), 5)) {
            throw new RespostaDeErro(429, new ErroPlano("rate_limit_exceeded",
                    "Calma! Aguarde um momento antes de comentar de novo."));
        }
        String texto = conteudo(json(corpo), true);
        UUID espelho = resolucao.resolver(slug).getId();
        String ip = ClienteIp.cru(req);
        return ResponseEntity.status(201).body(tx.execute(s -> {
            Comentario c = new Comentario();
            c.setCustomer(em.getReference(Usuario.class, eu.getId()));
            c.setPostEspelho(em.getReference(PostEspelho.class, espelho));
            c.setConteudo(texto);
            c.setIpAddress(ip);
            return Item.de(comentarios.saveAndFlush(c));
        }));
    }
}
