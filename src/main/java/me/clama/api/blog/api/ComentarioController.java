package me.clama.api.blog.api;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import me.clama.api.blog.Comentario;
import me.clama.api.blog.ComentarioRepository;
import me.clama.api.blog.ErroPlano;
import me.clama.api.comum.erro.NaoEncontrado;
import me.clama.api.identidade.Usuario;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * ComentarioUpdateDestroyView (views.py:298-341): IsAuthenticated, sem checar ban. GET e PATCH so do dono (admin
 * incluido no 403); DELETE do dono ou de admin. PATCH valida e depois olha a janela de 15 min (exatamente 15 ainda
 * edita). PUT nao tem rota (405).
 */
@RestController
class ComentarioController {
    static final String ROTA = "/api/blog/comments/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/";
    private static final Duration JANELA_DE_EDICAO = Duration.ofMinutes(15);

    private final ComentarioRepository comentarios;
    private final TransactionTemplate tx;
    private final Clock relogio;

    ComentarioController(ComentarioRepository comentarios, TransactionTemplate tx, Clock relogio) {
        this.comentarios = comentarios;
        this.tx = tx;
        this.relogio = relogio;
    }

    @GetMapping(ROTA)
    ComentariosController.Item ver(@PathVariable UUID id, @AuthenticationPrincipal Usuario eu) {
        return tx.execute(s -> ComentariosController.Item.de(doDono(id, eu)));
    }

    @PatchMapping(ROTA)
    ComentariosController.Item editar(@PathVariable UUID id, @AuthenticationPrincipal Usuario eu,
            @RequestBody(required = false) JsonNode corpo) {
        return tx.execute(s -> {
            Comentario c = doDono(id, eu);
            String texto = ComentariosController.conteudo(corpo, false);
            if (Duration.between(c.getCreatedAt(), OffsetDateTime.now(relogio)).compareTo(JANELA_DE_EDICAO) > 0) {
                throw new RespostaDeErro(400, new ErroPlano("comentario_muito_antigo",
                        "Esse comentário foi escrito há mais de 15 minutos e não pode mais ser editado."));
            }
            if (texto != null) {
                c.setConteudo(texto);
            }
            c.marcarAlterada();
            return ComentariosController.Item.de(comentarios.saveAndFlush(c));
        });
    }

    @DeleteMapping(ROTA)
    ResponseEntity<Void> apagar(@PathVariable UUID id, @AuthenticationPrincipal Usuario eu) {
        tx.executeWithoutResult(s -> {
            Comentario c = carregar(id);
            if (!c.getCustomer().getId().equals(eu.getId()) && !eu.isClamaAdmin()) {
                throw semPermissao("Sem permissão para apagar.");
            }
            comentarios.delete(c);
        });
        return ResponseEntity.noContent().build();
    }

    private Comentario carregar(UUID id) {
        return comentarios.findById(id).orElseThrow(() -> new NaoEncontrado("Comentario"));
    }

    private Comentario doDono(UUID id, Usuario eu) {
        Comentario c = carregar(id);
        if (!c.getCustomer().getId().equals(eu.getId())) {
            throw semPermissao("Sem permissão para editar.");
        }
        return c;
    }

    private static RespostaDeErro semPermissao(String detalhe) {
        return new RespostaDeErro(403, Map.of("detail", detalhe));
    }
}
