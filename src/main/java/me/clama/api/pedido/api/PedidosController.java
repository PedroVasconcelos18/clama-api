package me.clama.api.pedido.api;

import jakarta.servlet.http.HttpServletRequest;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.identidade.Usuario;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * PedidoCreateView: pedido pago de quem tem conta. Autenticacao e IsCustomerPasswordCurrent no SegurancaConfig
 * (SENHA_EM_DIA); sem side effect alem do INSERT.
 */
@RestController
class PedidosController {
    private final ValidacaoDePedido validacao;
    private final PedidoRepository pedidos;
    private final TransactionTemplate tx;

    PedidosController(ValidacaoDePedido validacao, PedidoRepository pedidos, TransactionTemplate tx) {
        this.validacao = validacao;
        this.pedidos = pedidos;
        this.tx = tx;
    }

    @PostMapping("/api/pedidos/")
    @ResponseStatus(HttpStatus.CREATED)
    @LimiteDeTaxa(escopo = "pedidos_create", limite = 10, janelaSegundos = 60)
    @LimiteDeTaxa(escopo = "email_pedido", limite = 5, janelaSegundos = 3600,
            chave = LimiteDeTaxa.Chave.EMAIL_DO_USUARIO)
    PedidoDtos.Criado criar(@AuthenticationPrincipal Usuario eu, @RequestBody(required = false) JsonNode corpo,
            HttpServletRequest req) {
        Pedido p = validacao.validar(corpo, false).pedido();
        ValidacaoDePedido.consentir(p, req);
        p.setUser(eu);
        return tx.execute(st -> PedidoDtos.Criado.de(pedidos.saveAndFlush(p)));
    }
}
