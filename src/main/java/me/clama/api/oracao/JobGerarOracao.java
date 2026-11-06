package me.clama.api.oracao;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * gerar_oracao_task (prayer_generation/tasks.py:31-181). Instancia {@code gerar-oracao/<id>}: um segundo disparo
 * enquanto este esta na fila ou rodando e no-op; depois, o guard de status pula. A chamada a Claude fica fora de
 * transacao. Qualquer falha que nao seja de credito (ErroDeGeracao, 529 esgotado, sem prompt ativo ou mais de um,
 * interrupcao no retry do cliente, banco) e retentada a cada 60 s, 3 vezes; esgotado, roda o
 * _handle_persistent_failure PRETENDIDO (no Django e codigo morto): reagenda em 300 s ate retry_count 3, depois erro.
 */
@Component
public class JobGerarOracao implements TipoDeJob<UUID> {
    private static final Logger LOG = LoggerFactory.getLogger(JobGerarOracao.class);
    public static final String NOME = "gerar-oracao";
    static final int MAX_REAGENDAMENTOS = 3;
    private static final Set<StatusPedido> JA_PROCESSADO = Set.of(StatusPedido.ORACAO_GERADA, StatusPedido.ENVIADA);

    private final PedidoRepository pedidos;
    private final ClienteClaude claude;
    private final JobEnviarOracao enviar;
    private final AlertaDeErro alerta;
    private final ObjectProvider<Fila> fila;
    private final TransactionTemplate tx;
    private final Duration espera;
    private final Duration reagendamento;

    JobGerarOracao(PedidoRepository pedidos, ClienteClaude claude, JobEnviarOracao enviar, AlertaDeErro alerta,
            ObjectProvider<Fila> fila, TransactionTemplate tx,
            @Value("${clama.oracao.gerar-espera:60s}") Duration espera,
            @Value("${clama.oracao.reagendamento:300s}") Duration reagendamento) {
        this.pedidos = pedidos;
        this.claude = claude;
        this.enviar = enviar;
        this.alerta = alerta;
        this.fila = fila;
        this.tx = tx;
        this.espera = espera;
        this.reagendamento = reagendamento;
    }

    public static String instancia(UUID id) {
        return NOME + "/" + id;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public Class<UUID> payload() {
        return UUID.class;
    }

    @Override
    public Duration esperaEntreTentativas() {
        return espera;
    }

    @Override
    public void executar(UUID id, int retentativa) {
        Pedido p = tx.execute(s -> {
            Pedido lido = pedidos.findById(id).orElse(null);
            if (lido != null && !JA_PROCESSADO.contains(lido.getStatus())) {
                lido.setStatus(StatusPedido.GERANDO_ORACAO);
                Hibernate.initialize(lido.getPlano());
            }
            return lido;
        });
        if (p == null) {
            LOG.warn("gerar_oracao_pedido_nao_encontrado (pedido_id={})", id);
            return;
        }
        if (JA_PROCESSADO.contains(p.getStatus())) {
            LOG.info("gerar_oracao_ja_processado (event=gerar_oracao_skipped, pedido_id={}, current_status={})", id,
                    p.getStatus().valor());
            return;
        }
        LOG.info("gerar_oracao_iniciando (event=gerar_oracao_started, pedido_id={}, retry_count={}, attempt={})", id,
                p.getRetryCount(), retentativa + 1);
        String oracao;
        try {
            oracao = claude.gerar(p);
        } catch (CreditosEsgotados e) {
            // so voltam quando o admin recarrega: erro direto, sem retry, e o admin destrava pelo reenviar
            tx.executeWithoutResult(s -> {
                Pedido atual = pedidos.findById(id).orElseThrow();
                atual.setStatus(StatusPedido.ERRO);
                atual.setLastError("credit_balance");
                alerta.registrar(id);
            });
            LOG.error("gerar_oracao_sem_creditos (event=gerar_oracao_no_credits, pedido_id={})", id);
            Sentry.captureMessage("Anthropic sem créditos — pedido travado aguardando admin", SentryLevel.ERROR);
            return;
        } catch (RuntimeException e) {
            LOG.warn("gerar_oracao_erro (event=gerar_oracao_error, pedido_id={}, attempt={}, error={})", id,
                    retentativa + 1, e.getMessage());
            throw e;
        }
        tx.executeWithoutResult(s -> {
            Pedido atual = pedidos.findById(id).orElseThrow();
            atual.setOracaoGerada(oracao);
            atual.setStatus(StatusPedido.ORACAO_GERADA);
            fila.getObject().enfileirar(enviar, JobEnviarOracao.instancia(id), id);
        });
        LOG.info("gerar_oracao_concluido (event=gerar_oracao_completed, pedido_id={})", id);
    }

    /**
     * _handle_persistent_failure. A linha da instancia ja saiu da fila: o reagendamento reaproveita a instancia, na
     * mesma transacao que grava aguardando_reenvio. O Sentry (capture_exception) vem do observador da fila.
     */
    @Override
    public void esgotou(UUID id, Throwable erro) {
        tx.executeWithoutResult(s -> {
            Pedido p = pedidos.findById(id).orElse(null);
            if (p == null || JA_PROCESSADO.contains(p.getStatus())) return;
            p.setLastError(truncar(erro == null ? "" : Objects.toString(erro.getMessage(), ""), 2000));
            if (p.getRetryCount() < MAX_REAGENDAMENTOS) {
                p.setRetryCount(p.getRetryCount() + 1);
                p.setStatus(StatusPedido.AGUARDANDO_REENVIO);
                fila.getObject().enfileirar(this, instancia(id), id, Instant.now().plus(reagendamento));
                LOG.info("gerar_oracao_reagendado (event=gerar_oracao_rescheduled, pedido_id={}, retry_count={}, "
                        + "delay_seconds={})", id, p.getRetryCount(), reagendamento.toSeconds());
            } else {
                p.setStatus(StatusPedido.ERRO);
                alerta.registrar(id);
                LOG.error("gerar_oracao_erro_definitivo (event=gerar_oracao_failed_permanently, pedido_id={}, "
                        + "retry_count={})", id, p.getRetryCount());
            }
        });
    }

    /** str(exc)[:2000]: o Python corta em code points. */
    static String truncar(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }
}
