package me.clama.api.pedido.api;

import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import me.clama.api.comum.antibot.Turnstile;
import me.clama.api.comum.antibot.TurnstileIndisponivel;
import me.clama.api.comum.email.EmailDescartavel;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.ratelimit.ClienteIp;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * DoacaoAnonimaCreateView: doacao sem conta (AllowAny; cookie presente segue a cadeia normal). Ordem: campos (400),
 * Turnstile (400 captcha_invalido), e-mail descartavel (400), INSERT com user nulo. O Turnstile roda fora de
 * transacao: nao segura conexao durante a chamada de rede.
 */
@RestController
class DoacoesController {
    private static final Logger LOG = LoggerFactory.getLogger(DoacoesController.class);

    private final ValidacaoDePedido validacao;
    private final Turnstile turnstile;
    private final PedidoRepository pedidos;
    private final TransactionTemplate tx;

    DoacoesController(ValidacaoDePedido validacao, Turnstile turnstile, PedidoRepository pedidos,
            TransactionTemplate tx) {
        this.validacao = validacao;
        this.turnstile = turnstile;
        this.pedidos = pedidos;
        this.tx = tx;
    }

    @PostMapping("/api/doacoes/")
    @ResponseStatus(HttpStatus.CREATED)
    @LimiteDeTaxa(escopo = "doacao_ip", limite = 10, janelaSegundos = 3600)
    PedidoDtos.Criado doar(@RequestBody(required = false) JsonNode corpo, HttpServletRequest req) {
        ValidacaoDePedido.Dados dados = validacao.validar(corpo, true);
        boolean humano;
        try {
            humano = turnstile.validar(dados.turnstileToken(), ClienteIp.validado(req));
        } catch (TurnstileIndisponivel e) {
            Sentry.captureException(e);
            LOG.error("Falha permanente ao validar Turnstile (doação) event=doacao_turnstile_falha_permanente", e);
            humano = false;
        }
        if (!humano) {
            throw new PastoralException(400, "captcha_invalido", "Token Turnstile inválido", ValidacaoDePedido.TURNSTILE);
        }
        Pedido p = dados.pedido();
        if (EmailDescartavel.eh(p.getEmail())) {
            throw new PastoralException(400, "email_descartavel", "E-mail descartável não permitido",
                    "Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.");
        }
        ValidacaoDePedido.consentir(p, req);
        p.setStatus(StatusPedido.AGUARDANDO_PAGAMENTO);
        p.setUser(null); // a conta e vinculada no webhook de pagamento
        return tx.execute(st -> PedidoDtos.Criado.de(pedidos.saveAndFlush(p)));
    }
}
