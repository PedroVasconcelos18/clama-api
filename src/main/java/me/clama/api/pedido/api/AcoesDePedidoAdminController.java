package me.clama.api.pedido.api;

import java.util.UUID;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.fila.Fila;
import me.clama.api.oracao.JobEnviarOracao;
import me.clama.api.oracao.JobGerarOracao;
import me.clama.api.oracao.JobReenviarOracao;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminPedidoReenviarView e AdminPedidoMarcarGratuitoView (admin_views.py, mapa §7.3 e §7.4). Acesso admin e CSRF do
 * SegurancaConfig. Pedido travado (o Django nao trava) e jobs na mesma transacao: so ficam visiveis no commit.
 */
@RestController
class AcoesDePedidoAdminController {
    private static final String PEDIDO =
            "/api/admin/pedidos/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/";

    private final PedidoRepository pedidos;
    private final Fila fila;
    private final JobGerarOracao gerar;
    private final JobReenviarOracao reenviar;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    AcoesDePedidoAdminController(PedidoRepository pedidos, Fila fila, JobGerarOracao gerar, JobReenviarOracao reenviar,
            TransactionTemplate tx, JdbcTemplate jdbc) {
        this.pedidos = pedidos;
        this.fila = fila;
        this.gerar = gerar;
        this.reenviar = reenviar;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    record Resposta(String status, String message) {}

    @PostMapping(PEDIDO + "reenviar/")
    Resposta reenviar(@PathVariable UUID id) {
        return tx.execute(s -> {
            Pedido p = travar(id);
            if (p.getOracaoGerada() == null || p.getOracaoGerada().isEmpty()) {
                if (p.getStatus() != StatusPedido.ERRO && p.getStatus() != StatusPedido.AGUARDANDO_REENVIO) {
                    throw new PastoralException(400, "no_prayer", "Pedido sem oração gerada",
                            "Este pedido ainda não tem oração gerada.");
                }
                p.setStatus(StatusPedido.PAGO);
                p.setRetryCount(0);
                p.setLastError("");
                // aguardando_reenvio: o gerar-oracao ja esta agendado (+300 s) e vem para agora. Nem enfileira nem
                // antecipa so com o gerar ainda rodando em erro (o caminho de creditos, janela de milissegundos entre
                // gravar erro e a linha sair da fila): o pedido fica pago sem job e a varredura de presos (Tarefa 8)
                // o leva a erro de novo.
                gerarOuAntecipar(id);
                return new Resposta("ok", "Regeração disparada");
            }
            // enviar-oracao do pipeline na fila ou em retentativa: ele faz o envio. Um reenvio junto mandaria a
            // oracao duas vezes, com chaves diferentes.
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from scheduled_tasks where task_name = ? and task_instance = ?)",
                    Boolean.class, JobEnviarOracao.NOME, JobEnviarOracao.instancia(id)))) {
                return new Resposta("ok", "Reenvio disparado");
            }
            // reenvio ja na fila (ou rodando): ele faz o envio, e o status fica como esta (nao volta enviada para
            // oracao_gerada sem job que a leve de volta)
            if (fila.enfileirar(reenviar, JobReenviarOracao.instancia(id),
                    new JobReenviarOracao.ReenvioDeOracao(id, UUID.randomUUID()))) {
                p.setStatus(StatusPedido.ORACAO_GERADA);
            }
            return new Resposta("ok", "Reenvio disparado");
        });
    }

    @PostMapping(PEDIDO + "marcar-gratuito/")
    Resposta marcarGratuito(@PathVariable UUID id) {
        return tx.execute(s -> {
            if (!travar(id).marcarComoGratuito()) {
                return new Resposta("ok", "Pedido já estava marcado como gratuito.");
            }
            gerarOuAntecipar(id);
            return new Resposta("ok", "Pedido marcado como gratuito. Gerando oração.");
        });
    }

    /** gerar-oracao/<id> para agora: enfileira, ou antecipa a instancia ja agendada. */
    private void gerarOuAntecipar(UUID id) {
        if (!fila.enfileirar(gerar, JobGerarOracao.instancia(id), id)) {
            fila.antecipar(gerar, JobGerarOracao.instancia(id));
        }
    }

    private Pedido travar(UUID id) {
        return pedidos.travar(id).orElseThrow(() -> new PastoralException(404, "not_found", "Pedido não encontrado",
                "Esse pedido não foi encontrado."));
    }
}
