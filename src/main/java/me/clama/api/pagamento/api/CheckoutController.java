package me.clama.api.pagamento.api;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.clama.api.comum.Dinheiro;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.pagamento.CobrancaPix;
import me.clama.api.pagamento.ErroDoProvedor;
import me.clama.api.pagamento.MercadoPago;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CheckoutView (clama/payments/api/views.py). AllowAny: quem tem o UUID gera ou le o Pix. O pedido fica travado
 * (SELECT ... FOR UPDATE) da leitura ate o commit, incluida a chamada ao Mercado Pago: a segunda requisicao
 * concorrente espera e reaproveita o Pix gravado. Erro do Mercado Pago sai da transacao, entao nada e gravado.
 */
@RestController
class CheckoutController {
    private static final Logger LOG = LoggerFactory.getLogger(CheckoutController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PASTORAL_PADRAO = "Tivemos um soluço com o pagamento. Pode tentar novamente em instantes.";
    /** MSG_PAYMENT_CONFIG_ERROR: credencial/conta, nada que a pessoa resolva. */
    static final String MSG_PAYMENT_CONFIG_ERROR = "Tivemos um soluço processando seu pagamento. Já estamos olhando — "
            + "tenta de novo em alguns instantes ou escreve pra contato@clama.me.";
    private static final List<String> PALAVRAS_DE_CREDENCIAL = List.of("credential", "credencial", "unauthorized",
            "não autorizado", "nao autorizado", "forbidden", "access token", "access_token", "invalid_token",
            "invalid access", "collector");

    record Pix(UUID pedido_id, String pix_qr_code, String pix_qr_code_base64) {}

    private final PedidoRepository pedidos;
    private final MercadoPago mercadoPago;
    private final TransactionTemplate tx;
    private final int minimoCentavos;

    CheckoutController(PedidoRepository pedidos, MercadoPago mercadoPago, TransactionTemplate tx,
            @Value("${MERCADOPAGO_MIN_VALOR_CENTAVOS:1}") int minimoCentavos) {
        this.pedidos = pedidos;
        this.mercadoPago = mercadoPago;
        this.tx = tx;
        this.minimoCentavos = minimoCentavos;
    }

    @PostMapping("/api/pedidos/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/checkout/")
    @LimiteDeTaxa(escopo = "pedidos_checkout", limite = 10, janelaSegundos = 60)
    Pix checkout(@PathVariable UUID id) {
        return tx.execute(st -> {
            Pedido p = pedidos.travar(id).orElseThrow(() -> new PastoralException(404, "not_found",
                    "Pedido não encontrado", "Não encontramos esse pedido. Verifique se o link está correto."));
            validar(p);
            if (preenchido(p.getProviderPaymentId()) && preenchido(p.getPixQrCode())) {
                LOG.info("Checkout reused (event=checkout_reused, pedido_id={}, provider_payment_id={})", p.getId(),
                        p.getProviderPaymentId());
                return new Pix(p.getId(), p.getPixQrCode(), p.getPixQrCodeBase64());
            }
            CobrancaPix c;
            try {
                c = mercadoPago.criarCobrancaPix(p);
            } catch (ErroDoProvedor e) {
                throw paraResposta(e, p.getId());
            }
            p.setProviderPaymentId(c.providerPaymentId());
            p.setProviderCheckoutUrl(null); // a ticket_url pode passar do URLField; o Pix aparece no app pelo QR
            p.setPixQrCode(c.pixQrCode());
            p.setPixQrCodeBase64(c.pixQrCodeBase64());
            pedidos.saveAndFlush(p);
            LOG.info("Checkout created (event=checkout_created, pedido_id={}, provider_payment_id={})", p.getId(),
                    c.providerPaymentId());
            return new Pix(p.getId(), c.pixQrCode(), c.pixQrCodeBase64());
        });
    }

    private void validar(Pedido p) {
        if (p.getStatus() != StatusPedido.AGUARDANDO_PAGAMENTO) {
            throw new PastoralException(409, "pedido_ja_pago", "Pedido já foi pago ou processado",
                    "Esse pedido já foi pago, vamos te encaminhar para a confirmação.");
        }
        if (!preenchido(p.getCpfCnpj())) {
            throw new PastoralException(422, "cpf_cnpj_obrigatorio", "CPF/CNPJ é obrigatório para gerar a cobrança",
                    "Precisamos do seu CPF para gerar a cobrança. Volte ao formulário e complete o cadastro.");
        }
        if (p.getValorCentavos() < minimoCentavos) {
            String minimo = Dinheiro.centavosParaReaisStr(minimoCentavos);
            throw new PastoralException(422, "valor_abaixo_do_minimo", "Valor da cobrança (" + p.valorReaisStr()
                    + ") é menor que o mínimo aceito (" + minimo + ")", "O valor mínimo aceito pelo processador de "
                    + "pagamentos é " + minimo + ". Escolha um valor igual ou maior pra continuar.");
        }
    }

    /** 4xx do Mercado Pago: dados/config recusados (422). Rede, timeout, 5xx ou corpo estranho: 503 (502 nao). */
    static PastoralException paraResposta(ErroDoProvedor e, UUID pedido) {
        Integer status = e.upstreamStatus();
        boolean quatroXX = status != null && status >= 400 && status < 500;
        boolean credencial = quatroXX && ehDeCredencial(descricao(e.upstreamBody()));
        String corpo = e.upstreamBody() == null ? null
                : e.upstreamBody().substring(0, Math.min(e.upstreamBody().length(), 500));
        LOG.error("Checkout failed (event=checkout_failed, pedido_id={}, error={}, upstream_status={}, admin_side={}, "
                + "upstream_body={})", pedido, e.getMessage(), status, credencial, corpo);
        if (credencial) {
            // sem o corpo (pode ter PII): o detalhe fica no log
            Sentry.captureMessage("Mercado Pago admin-side error no checkout (ver logs)", SentryLevel.ERROR);
        }
        return new PastoralException(quatroXX ? 422 : 503, "payment_provider_error", e.getMessage(),
                credencial ? MSG_PAYMENT_CONFIG_ERROR : PASTORAL_PADRAO);
    }

    /** cause[0].description, senao message; "" se o corpo nao for um objeto JSON. */
    static String descricao(String corpo) {
        JsonNode n;
        try {
            n = corpo == null ? null : JSON.readTree(corpo);
        } catch (RuntimeException naoEhJson) {
            return "";
        }
        if (n == null || !n.isObject()) return "";
        JsonNode causa = n.path("cause");
        if (causa.isArray() && !causa.isEmpty() && causa.get(0).path("description").isString()) {
            return causa.get(0).get("description").asString().strip();
        }
        return n.path("message").isString() ? n.get("message").asString().strip() : "";
    }

    static boolean ehDeCredencial(String descricao) {
        String d = descricao.toLowerCase(Locale.ROOT);
        return !d.isEmpty() && PALAVRAS_DE_CREDENCIAL.stream().anyMatch(d::contains);
    }

    private static boolean preenchido(String v) {
        return v != null && !v.isEmpty();
    }
}
