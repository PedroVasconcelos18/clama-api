package me.clama.api.oracao;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.clama.api.comum.fila.Fila;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O signal post_save de orders/signals.py: todo ponto que poe um pedido em erro chama {@link #registrar} na mesma
 * transacao. O job alerta-admin entra na fila nessa transacao (so fica visivel depois do commit, o on_commit do
 * Django). Um alerta por pedido por hora (o cache alert_sent), marcado so depois do commit: ao contrario do Django, um
 * rollback nao suprime o proximo alerta.
 */
// ponytail: dedup em memoria, uma instancia; a instancia deterministica da fila ja cobre o alerta ainda pendente
@Component
public class AlertaDeErro {
    private static final Logger LOG = LoggerFactory.getLogger(AlertaDeErro.class);
    static final Duration JANELA = Duration.ofHours(1);

    private final Map<UUID, Instant> enviados = new ConcurrentHashMap<>();
    private final ObjectProvider<Fila> fila;
    private final JobAlertaAdmin job;
    Clock relogio = Clock.systemUTC();

    AlertaDeErro(ObjectProvider<Fila> fila, JobAlertaAdmin job) {
        this.fila = fila;
        this.job = job;
    }

    /** Exige a transacao que grava o status erro. */
    public void registrar(UUID pedidoId) {
        Instant agora = relogio.instant();
        enviados.values().removeIf(t -> !t.plus(JANELA).isAfter(agora));
        if (enviados.containsKey(pedidoId)) {
            LOG.debug("Alert already sent for pedido (event=alert_throttled, pedido_id={})", pedidoId);
            return;
        }
        if (!fila.getObject().enfileirar(job, JobAlertaAdmin.instancia(pedidoId), pedidoId)) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enviados.put(pedidoId, agora);
            }
        });
        LOG.info("Alert dispatched for pedido ERRO (event=alert_dispatched, pedido_id={})", pedidoId);
    }

    /** cache.clear(): so testes. */
    public void limpar() {
        enviados.clear();
    }
}
