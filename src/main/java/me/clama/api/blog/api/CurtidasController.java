package me.clama.api.blog.api;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import me.clama.api.blog.EntidadeDoBlog;
import me.clama.api.blog.ErroPlano;
import me.clama.api.blog.JanelaFixa;
import me.clama.api.blog.ResolucaoDoEspelho;
import me.clama.api.identidade.Usuario;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ReacaoToggleView (views.py:348-421): IsUnbannedCustomer (SegurancaConfig), 30/min na janela fixa, depois o toggle.
 * R7: apagar ou INSERT ... ON CONFLICT DO NOTHING; dois cliques simultaneos deixam uma curtida so (o Django dava 500).
 * Contagem depois do commit.
 */
@RestController
class CurtidasController {
    private final ResolucaoDoEspelho resolucao;
    private final JanelaFixa janela;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock relogio;

    CurtidasController(ResolucaoDoEspelho resolucao, JanelaFixa janela, JdbcTemplate jdbc, TransactionTemplate tx,
            Clock relogio) {
        this.resolucao = resolucao;
        this.janela = janela;
        this.jdbc = jdbc;
        this.tx = tx;
        this.relogio = relogio;
    }

    record Curtida(boolean liked, long like_count) {}

    @PostMapping("/api/blog/posts/{slug:[-a-zA-Z0-9_]+}/like/")
    Curtida curtir(@PathVariable String slug, @AuthenticationPrincipal Usuario eu) {
        if (janela.excedeu("curtida", eu.getId(), 30)) {
            throw new RespostaDeErro(429, new ErroPlano("rate_limit_exceeded",
                    "Calma! Aguarde um momento antes de reagir de novo."));
        }
        UUID espelho = resolucao.resolver(slug).getId();
        boolean curtiu = tx.execute(s -> {
            if (jdbc.update("DELETE FROM blog_reacao WHERE post_espelho_id = ? AND customer_id = ? AND tipo = 'like'",
                    espelho, eu.getId()) > 0) {
                return false;
            }
            OffsetDateTime agora = EntidadeDoBlog.agora(relogio);
            jdbc.update("INSERT INTO blog_reacao (id, created_at, updated_at, tipo, customer_id, post_espelho_id)"
                    + " VALUES (?, ?, ?, 'like', ?, ?) ON CONFLICT DO NOTHING", UUID.randomUUID(), agora, agora,
                    eu.getId(), espelho);
            return true;
        });
        return new Curtida(curtiu, jdbc.queryForObject(
                "SELECT count(*) FROM blog_reacao WHERE post_espelho_id = ? AND tipo = 'like'", Long.class, espelho));
    }
}
