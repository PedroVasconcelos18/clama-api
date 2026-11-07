package me.clama.api.pagamento.webhook;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import me.clama.api.comum.fila.Fila;
import me.clama.api.conta.JobBoasVindas;
import me.clama.api.conta.ProvisionamentoDeConta;
import me.clama.api.instituicao.repasse.RegistroDeRepasse;
import me.clama.api.oracao.JobGerarOracao;
import me.clama.api.pagamento.ErroDoProvedor;
import me.clama.api.pagamento.MercadoPago;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.TransicaoInvalida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O processamento de um evento payment do Mercado Pago depois do registro (mapa 7 §2.2), compartilhado pelo webhook
 * (4B) e pelo reprocessar do admin (D9): GET do pagamento sem transacao aberta, depois uma transacao so com o pedido
 * travado (SELECT ... FOR UPDATE): pago, repasse, conta, PROCESSADO e os jobs gerar-oracao e boas-vindas (visiveis na
 * fila so com o commit). Quem chega depois ao lock acha o pedido pago e sai already_paid (ou already_processed, se a
 * linha ja ficou terminal); o webhook e o admin passam pelo mesmo caminho, entao a corrida entre eles e a de dois
 * webhooks. Erro inesperado desfaz o processamento inteiro e marca ERRO depois, fora dele.
 *
 * <p>{@code naMesmaTransacao} roda junto de cada escrita final do evento (a auditoria do admin; o webhook passa um
 * no-op): na transacao do processamento quando ela da certo, e na da marcacao de IGNORADO/ERRO nos outros casos.
 */
@Component
public class ProcessamentoDePagamento {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessamentoDePagamento.class);
    /**
     * 4xx do GET do pagamento tratados como 5xx (decisao do controlador, fix 1 da tarefa 5): 408 e 429 passam, e
     * 401/403 (token errado ou trocado) se corrigem com config. Ignorar descartaria para sempre um pagamento aprovado.
     * O Django retenta 429 no SDK e acaba em 500; 401/403 la descartam o pagamento em silencio, aqui nao.
     */
    static final Set<Integer> TRANSIENTES = Set.of(401, 403, 408, 429);

    /** Desfecho; os quatro primeiros sao o {"status": ...} do webhook. */
    public enum Resultado {
        OK("ok"), IGNORADO("ignored"), JA_PAGO("already_paid"), JA_PROCESSADO("already_processed"),
        /** GET do pagamento falhou (5xx, rede ou TRANSIENTES): ERRO, reprocessavel. */
        FALHA_NO_FETCH(null),
        /** Excecao no processamento (desfeito): ERRO, reprocessavel. */
        FALHA_INTERNA(null);

        public final String status;

        Resultado(String status) {
            this.status = status;
        }
    }

    private final WebhookEventoRepository eventos;
    private final MercadoPago mercadoPago;
    private final PedidoRepository pedidos;
    private final RegistroDeRepasse repasse;
    private final ProvisionamentoDeConta provisionamento;
    private final Fila fila;
    private final JobGerarOracao gerar;
    private final JobBoasVindas boasVindas;
    private final TransactionTemplate tx;

    ProcessamentoDePagamento(WebhookEventoRepository eventos, MercadoPago mercadoPago, PedidoRepository pedidos,
            RegistroDeRepasse repasse, ProvisionamentoDeConta provisionamento, Fila fila, JobGerarOracao gerar,
            JobBoasVindas boasVindas, TransactionTemplate tx) {
        this.eventos = eventos;
        this.mercadoPago = mercadoPago;
        this.pedidos = pedidos;
        this.repasse = repasse;
        this.provisionamento = provisionamento;
        this.fila = fila;
        this.gerar = gerar;
        this.boasVindas = boasVindas;
        this.tx = tx;
    }

    /** Evento payment nao terminal, com o data.id ja conferido (nao vazio). Nunca abre transacao antes do GET. */
    public Resultado processar(UUID eventoId, String externalId, String dataId, Runnable naMesmaTransacao) {
        // o GET ao MP nunca roda dentro de transacao: e o que sustenta o exactly-once do dinheiro
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "processar abre as próprias transações");
        MercadoPago.PagamentoMP pagamento;
        try {
            pagamento = mercadoPago.buscarPagamento(dataId);
        } catch (ErroDoProvedor e) {
            Integer upstream = e.upstreamStatus();
            if (upstream != null && upstream >= 400 && upstream < 500 && !TRANSIENTES.contains(upstream)) {
                // 4xx (pagamento inexistente, requisicao recusada) nao e transiente: 200, sem loop de reenvio
                marcar(naMesmaTransacao, () -> eventos.marcarIgnorado(eventoId, "fetch 4xx do Mercado Pago: "
                        + upstream));
                Sentry.captureMessage("Mercado Pago webhook: fetch 4xx (config/credencial ou pagamento inexistente)",
                        SentryLevel.ERROR);
                LOG.warn("Mercado Pago webhook: payment fetch 4xx (non-retriable) (event=mercadopago_webhook_fetch_4xx, "
                        + "external_event_id={}, upstream_status={})", externalId, upstream);
                return Resultado.IGNORADO;
            }
            // 5xx/rede e os 4xx de TRANSIENTES: linha reprocessavel (o webhook responde 500 e o MP reenvia)
            marcar(naMesmaTransacao, () -> eventos.marcarErro(eventoId, ate2000(mensagem(e))));
            Sentry.captureException(e);
            LOG.warn("Mercado Pago webhook: payment fetch failed (event=mercadopago_webhook_fetch_failed, "
                    + "external_event_id={}, data_id={})", externalId, dataId);
            return Resultado.FALHA_NO_FETCH;
        }
        if (pagamento.status() != MercadoPago.StatusPagamento.APROVADO) {
            marcar(naMesmaTransacao, () -> eventos.marcarIgnorado(eventoId, "pagamento não-aprovado: "
                    + Objects.toString(pagamento.statusBruto(), "None")));
            return Resultado.IGNORADO;
        }
        String referencia = pagamento.externalReference();
        if (referencia == null || referencia.isEmpty()) {
            marcar(naMesmaTransacao, () -> eventos.marcarIgnorado(eventoId, "pagamento sem external_reference"));
            return Resultado.IGNORADO;
        }

        Boolean achou;
        try {
            achou = tx.execute(s -> {
                boolean r = aplicar(eventoId, uuidDoDjango(referencia));
                if (r) {
                    naMesmaTransacao.run();
                }
                return r;
            });
        } catch (TransicaoInvalida e) {
            // 409 do guard (pedido ja pago, inclusive por outra notificacao do mesmo pagamento): sucesso idempotente.
            // Linha ja terminal = reentrega concorrente do mesmo id (ou o reprocesso do admin) que acabou de processar.
            return marcar(naMesmaTransacao, () -> eventos.marcarIgnorado(eventoId, "pedido já processado (idempotente)"))
                    ? Resultado.JA_PAGO : Resultado.JA_PROCESSADO;
        } catch (RuntimeException e) {
            marcar(naMesmaTransacao, () -> eventos.marcarErro(eventoId, ate2000(mensagem(e))));
            Sentry.captureException(e);
            LOG.error("Mercado Pago webhook error (event=mercadopago_webhook_error, external_event_id={})", externalId,
                    e);
            return Resultado.FALHA_INTERNA;
        }
        if (!Boolean.TRUE.equals(achou)) {
            marcar(naMesmaTransacao, () -> eventos.marcarIgnorado(eventoId, "pedido não encontrado: " + referencia));
            return Resultado.IGNORADO;
        }
        LOG.info("Mercado Pago webhook: payment confirmed (event=mercadopago_webhook_payment_confirmed, pedido_id={}, "
                + "external_event_id={})", referencia, externalId);
        return Resultado.OK;
    }

    /** O bloco transaction.atomic() do Django. false: pedido inexistente (ou referencia que nao e UUID). */
    private boolean aplicar(UUID eventoId, UUID pedidoId) {
        Pedido p = pedidoId == null ? null : pedidos.travar(pedidoId).orElse(null);
        if (p == null) {
            return false;
        }
        p.marcarComoPago();
        repasse.registrar(p);
        ProvisionamentoDeConta.Conta conta = provisionamento.provisionarOuVincular(p);
        if (!eventos.marcarProcessado(eventoId, p.getId())) {
            LOG.warn("Mercado Pago webhook: evento ja terminal ao marcar processado (copia paralela) "
                    + "(event=mercadopago_webhook_mark_processed_skipped, webhook_evento_id={}, pedido_id={})",
                    eventoId, p.getId());
        }
        fila.enfileirar(gerar, JobGerarOracao.instancia(p.getId()), p.getId());
        // credenciais so quando a conta nasceu aqui; no vinculo o recibo e a propria oracao
        if (conta.criada()) {
            Long userId = conta.usuario().getId();
            fila.enfileirar(boasVindas, JobBoasVindas.instancia(userId), userId);
        }
        return true;
    }

    /** Marcacao de IGNORADO/ERRO (um UPDATE) e o naMesmaTransacao numa transacao propria. */
    private boolean marcar(Runnable naMesmaTransacao, BooleanSupplier marcacao) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            boolean mudou = marcacao.getAsBoolean();
            naMesmaTransacao.run();
            return mudou;
        }));
    }

    /**
     * O uuid.UUID(ref) do UUIDField: aceita "urn:uuid:", chaves, hifens em qualquer lugar e maiusculas; null se nao
     * for UUID (o get() levanta ValidationError e o Django marca "pedido nao encontrado").
     */
    static UUID uuidDoDjango(String ref) {
        String hex = ref.replace("urn:", "").replace("uuid:", "").replaceAll("^[{}]+|[{}]+$", "").replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
    }

    /** str(exc): a mensagem (o nome da classe se nao houver). */
    private static String mensagem(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    /** str(exc)[:2000], em code points como o Python. */
    private static String ate2000(String s) {
        return s.codePointCount(0, s.length()) <= 2000 ? s : s.substring(0, s.offsetByCodePoints(0, 2000));
    }
}
