package me.clama.api.freemium;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * reconciliar_pedidos_freemium_orfaos do Django: pedido freemium em aguardando_confirmacao_email ha mais de 48 h vai
 * para erro e perde os tokens. Sem AlertaDeErro (o QuerySet.update do Django nao dispara o signal; um alerta por pedido
 * mandaria N e-mails com PII no backlog): so warning e um evento agregado no Sentry. LISTAR so loga os ids e devolve
 * quantos seriam reconciliados, com 0 tokens apagados.
 */
@Component
public class ReconciliacaoFreemium implements TarefaAgendada {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliacaoFreemium.class);
    public static final String NOME = "reconciliar-freemium";
    static final Duration LIMIAR = Duration.ofHours(48);
    static final String ERRO = "email_confirmacao_nao_entregue";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    ReconciliacaoFreemium(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public boolean alteraDados() {
        return true;
    }

    @Override
    public ResultadoDaTarefa executar(ModoDeExecucao modo) {
        OffsetDateTime agora = OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
        OffsetDateTime corte = agora.minus(LIMIAR);
        int reconciliados = 0;
        int tokens = 0;
        if (modo == ModoDeExecucao.EXECUTAR) {
            // UPDATE e DELETE na mesma transacao (no Django sao dois autocommits)
            int[] n = tx.execute(s -> {
                List<UUID> ids = jdbc.queryForList("""
                        update orders_pedido set status = 'erro', last_error = ?, updated_at = ?
                        where eh_gratuito and status = 'aguardando_confirmacao_email' and created_at < ?
                        returning id""", UUID.class, ERRO, agora, corte);
                if (ids.isEmpty()) return new int[] {0, 0};
                int t = jdbc.update("delete from freemium_freemiumconfirmationtoken where pedido_id = any(?)",
                        (Object) ids.toArray(UUID[]::new));
                return new int[] {ids.size(), t};
            });
            reconciliados = n[0];
            tokens = n[1];
            if (reconciliados == 0) {
                LOG.info("freemium_reconcile_noop: sem candidatos");
            } else {
                LOG.warn("freemium_pedidos_orfaos_reconciliados n_reconciliados={} n_tokens_deletados={} threshold_hours={}",
                        reconciliados, tokens, LIMIAR.toHours());
                Sentry.captureMessage("Freemium: " + reconciliados + " pedidos órfãos reconciliados (>48h "
                        + "em AGUARDANDO_CONFIRMACAO_EMAIL).", SentryLevel.WARNING);
            }
        } else {
            List<UUID> ids = jdbc.queryForList("""
                    select id from orders_pedido
                    where eh_gratuito and status = 'aguardando_confirmacao_email' and created_at < ?
                    order by created_at""", UUID.class, corte);
            reconciliados = ids.size();
            LOG.info("Pedidos freemium orfaos (modo LISTAR, nada alterado): {}", ids);
        }
        var resumo = new LinkedHashMap<String, Object>();
        resumo.put("n_reconciliados", reconciliados);
        resumo.put("n_tokens_deletados", tokens);
        return new ResultadoDaTarefa(NOME, modo, resumo);
    }
}
