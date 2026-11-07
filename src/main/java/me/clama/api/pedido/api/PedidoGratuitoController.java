package me.clama.api.pedido.api;

import jakarta.servlet.http.HttpServletRequest;
import me.clama.api.catalogo.plano.PlanoGratuito;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.identidade.Usuario;
import me.clama.api.oracao.JobGerarOracao;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * PedidoGratuitoCreateView: pedido gratuito de quem tem conta (SENHA_EM_DIA no SegurancaConfig). Nasce
 * gerando_oracao e enfileira o gerar-oracao na mesma transacao do INSERT (o on_commit do Django).
 */
@RestController
class PedidoGratuitoController {
    private final ValidacaoDePedido validacao;
    private final PedidoRepository pedidos;
    private final PlanoGratuito planoGratuito;
    private final JobGerarOracao gerar;
    private final ObjectProvider<Fila> fila;
    private final TransactionTemplate tx;

    PedidoGratuitoController(ValidacaoDePedido validacao, PedidoRepository pedidos, PlanoGratuito planoGratuito,
            JobGerarOracao gerar, ObjectProvider<Fila> fila, TransactionTemplate tx) {
        this.validacao = validacao;
        this.pedidos = pedidos;
        this.planoGratuito = planoGratuito;
        this.gerar = gerar;
        this.fila = fila;
        this.tx = tx;
    }

    @PostMapping("/api/pedidos/gratuito/")
    @ResponseStatus(HttpStatus.CREATED)
    @LimiteDeTaxa(escopo = "pedidos_create", limite = 10, janelaSegundos = 60)
    @LimiteDeTaxa(escopo = "email_pedido", limite = 5, janelaSegundos = 3600,
            chave = LimiteDeTaxa.Chave.EMAIL_DO_USUARIO)
    PedidoDtos.Criado criar(@AuthenticationPrincipal Usuario eu, @RequestBody(required = false) JsonNode corpo,
            HttpServletRequest req) {
        Pedido p = validacao.validarGratuito(corpo);
        ValidacaoDePedido.consentir(p, req);
        p.setUser(eu);
        // _get_plano_gratuito: so depois de validar (no Django, o create do serializer)
        p.setPlano(planoGratuito.obter());
        p.setEhGratuito(true);
        p.setStatus(StatusPedido.GERANDO_ORACAO);
        return tx.execute(st -> {
            Pedido salvo = pedidos.saveAndFlush(p);
            fila.getObject().enfileirar(gerar, JobGerarOracao.instancia(salvo.getId()), salvo.getId());
            return PedidoDtos.Criado.de(salvo);
        });
    }
}
