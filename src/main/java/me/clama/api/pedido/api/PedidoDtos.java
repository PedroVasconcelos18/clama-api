package me.clama.api.pedido.api;

import java.util.UUID;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.pedido.Pedido;

final class PedidoDtos {
    private PedidoDtos() {}

    /** PedidoResponseSerializer (201 de POST /api/pedidos/ e /api/doacoes/). */
    record Criado(UUID id, String status, UUID instituicao, String valor_reais_str, String canal_entrega,
            String created_at) {
        static Criado de(Pedido p) {
            return new Criado(p.getId(), p.getStatus().valor(),
                    p.getInstituicao() == null ? null : p.getInstituicao().getId(), p.valorReaisStr(),
                    p.getCanalEntrega().valor(), DatasDrf.dataHora(p.getCreatedAt()));
        }
    }
}
