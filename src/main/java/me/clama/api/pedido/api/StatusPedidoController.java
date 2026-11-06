package me.clama.api.pedido.api;

import java.util.UUID;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.pedido.PedidoRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * PedidoStatusView: AllowAny, o UUID e o segredo (nao checa dono). Qualquer falha ao carregar vira 404 pastoral. A
 * regex e o conversor uuid do Django: outro formato nao tem rota e cai no 404 do dispatcher.
 */
@RestController
class StatusPedidoController {
    private final PedidoRepository pedidos;
    private final TransactionTemplate tx;

    StatusPedidoController(PedidoRepository pedidos, TransactionTemplate tx) {
        this.pedidos = pedidos;
        this.tx = tx;
    }

    @GetMapping("/api/pedidos/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/")
    @LimiteDeTaxa(escopo = "pedidos_status", limite = 60, janelaSegundos = 60)
    PedidoDtos.Status status(@PathVariable UUID id) {
        try {
            return tx.execute(st -> pedidos.findById(id).map(PedidoDtos.Status::de).orElseThrow());
        } catch (RuntimeException e) {
            throw new PastoralException(404, "not_found", "Pedido não encontrado",
                    "Não encontramos esse pedido. Pode tentar novamente?");
        }
    }
}
