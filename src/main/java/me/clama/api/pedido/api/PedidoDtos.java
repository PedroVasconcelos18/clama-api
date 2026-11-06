package me.clama.api.pedido.api;

import java.util.UUID;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.StatusPedido;

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

    /** PedidoStatusSerializer (GET /api/pedidos/{id}/). Sem dados pessoais. */
    record Status(UUID id, String status, String plano, UUID instituicao, String valor_reais_str, String canal_entrega,
            String created_at, String pastoral_message, String oracao_gerada, String pix_qr_code,
            String pix_qr_code_base64) {
        /** MSG_ERRO_CREDITOS_24H. */
        static final String MSG_ERRO_CREDITOS_24H = "Recebemos seu pedido com carinho. Sua oração será preparada e "
                + "enviada em até 24 horas. Se precisar falar com alguém antes, escreva pra contato@clama.me.";

        static Status de(Pedido p) {
            boolean creditos = p.getStatus() == StatusPedido.ERRO && "credit_balance".equals(p.getLastError());
            String oracao = p.getStatus() == StatusPedido.ENVIADA && !p.getOracaoGerada().isEmpty()
                    ? p.getOracaoGerada() : null;
            return new Status(p.getId(), p.getStatus().valor(), p.getPlano().getNome(),
                    p.getInstituicao() == null ? null : p.getInstituicao().getId(), p.valorReaisStr(),
                    p.getCanalEntrega().valor(), DatasDrf.dataHora(p.getCreatedAt()),
                    creditos ? MSG_ERRO_CREDITOS_24H : null, oracao, p.getPixQrCode(), p.getPixQrCodeBase64());
        }
    }
}
