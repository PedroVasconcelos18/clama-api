package me.clama.api.blog.webhook;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import me.clama.api.comum.api.FormularioDjango;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.pagamento.webhook.WebhookEvento;
import me.clama.api.pagamento.webhook.WebhookEventoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * WordPressWebhookView (blog/api/webhooks.py), com as respostas do mapa §4.3. A assinatura ja foi conferida pelo
 * {@link AssinaturaWordPress}, que deixou o corpo cru no atributo do request (sem ele o binding falha fechado, sem
 * ler corpo nao conferido). Registro, IGNORADO e o job sincronizar-espelho numa transacao so (o job so fica visivel
 * na fila com o commit, o on_commit do Django).
 *
 * <p>Divergencias de proposito: reentrega com o evento ainda RECEBIDO nao enfileira um 2o job (instancia
 * deterministica); evento_id &gt; 120 ou tipo &gt; 80 caracteres e JSON que nao e objeto dao 400 invalid_payload (o
 * Django da 500); multipart nao e aceito (o WordPress manda form-urlencoded).
 */
@RestController
class WebhookWordPressController {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookWordPressController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** EVENTOS_ACEITOS: o resto e IGNORADO com 200 (erro faria o WordPress reenviar o que nunca interessa). */
    static final Set<String> ACEITOS = Set.of("post_publicado", "post_atualizado", "post_removido");

    private final WebhookEventoRepository eventos;
    private final Fila fila;
    private final JobSincronizarEspelho job;
    private final TransactionTemplate tx;

    WebhookWordPressController(WebhookEventoRepository eventos, Fila fila, JobSincronizarEspelho job,
            TransactionTemplate tx) {
        this.eventos = eventos;
        this.fila = fila;
        this.job = job;
        this.tx = tx;
    }

    @PostMapping(AssinaturaWordPress.CAMINHO)
    @LimiteDeTaxa.Nenhum
    ResponseEntity<Map<String, String>> receber(HttpServletRequest req,
            @RequestAttribute(AssinaturaWordPress.CORPO) byte[] corpo) {
        ObjectNode dados = dados(req.getContentType(), corpo);
        String eventoId = dados == null ? "" : TextoPython.strip(TextoPython.strOuVazio(dados.get("evento_id")));
        String tipo = dados == null ? "" : TextoPython.strip(TextoPython.strOuVazio(dados.get("tipo")));
        // varchar(120) e varchar(80): o Django estoura DataError (500); aqui e payload invalido
        if (eventoId.isEmpty() || tipo.isEmpty() || eventoId.codePointCount(0, eventoId.length()) > 120
                || tipo.codePointCount(0, tipo.length()) > 80) {
            LOG.warn("wordpress_webhook_payload_invalido (event=wordpress_webhook_payload_invalido, tem_evento_id={}, "
                    + "tem_tipo={})", !eventoId.isEmpty(), !tipo.isEmpty());
            return ResponseEntity.badRequest().body(Map.of("status", "invalid_payload"));
        }
        String resposta = tx.execute(s -> {
            WebhookEvento evento = eventos.registrar(WebhookEventoRepository.WORDPRESS, eventoId, tipo,
                    JSON.writeValueAsString(dados));
            if (evento.terminal()) {
                LOG.info("wordpress_webhook_ja_processado (event=wordpress_webhook_ja_processado, evento_id={}, "
                        + "status={})", eventoId, evento.status());
                return "already_processed";
            }
            if (!ACEITOS.contains(tipo)) {
                eventos.marcarStatus(evento.id(), WebhookEvento.IGNORADO);
                LOG.info("wordpress_webhook_ignorado (event=wordpress_webhook_ignorado, tipo={})", tipo);
                return "ignored";
            }
            fila.enfileirar(job, JobSincronizarEspelho.instancia(evento.id()), evento.id());
            return "ok";
        });
        return ResponseEntity.ok(Map.of("status", resposta));
    }

    /**
     * request.data do DRF (JSONParser e FormParser) sobre o corpo cru: vazio vale {}; form vira o ultimo valor de cada
     * chave, tudo texto; JSON fica com os tipos originais (null se nao for objeto); outro tipo e 415.
     */
    static ObjectNode dados(String contentType, byte[] corpo) {
        if (corpo.length == 0) {
            return JSON.createObjectNode();
        }
        String tipo = FormularioDjango.tipo(contentType);
        if (tipo.equals("application/json")) {
            try {
                return JSON.readTree(corpo) instanceof ObjectNode o ? o : null;
            } catch (JacksonException e) {
                throw new HttpMessageNotReadableException("JSON invalido", e, null);
            }
        }
        if (!tipo.equals("application/x-www-form-urlencoded")) {
            throw new FormularioDjango.TipoNaoSuportado(contentType == null ? "" : contentType);
        }
        // QueryDict(corpo): parse_qsl com keep_blank_values, separado so por "&"
        ObjectNode campos = JSON.createObjectNode();
        for (String par : new String(corpo, StandardCharsets.UTF_8).split("&")) {
            if (par.isEmpty()) {
                continue;
            }
            int igual = par.indexOf('=');
            campos.put(decodificar(igual < 0 ? par : par.substring(0, igual)),
                    igual < 0 ? "" : decodificar(par.substring(igual + 1)));
        }
        return campos;
    }

    /** unquote_plus; escape invalido fica como esta (ponytail: o token inteiro, o Python so o escape ruim). */
    private static String decodificar(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException escapeInvalido) {
            return s.replace('+', ' ');
        }
    }
}
