package me.clama.api.oracao;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.FileDocumentSource;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import me.clama.api.comum.observabilidade.SentryConfig;
import me.clama.api.comum.retry.Retentativa;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.documento.DocumentoContexto;
import me.clama.api.documento.DocumentoRepository;
import me.clama.api.pedido.Pedido;
import me.clama.api.prompt.PromptAtivo;
import me.clama.api.prompt.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * AnthropicClient.gerar_oracao (clama/prayer_generation/services/anthropic_client.py) com o SDK oficial da Anthropic
 * para Java: mesmo payload que o SDK Python mandava (POST /v1/messages, nao-beta), com os documentos de contexto por
 * file_id e o header beta da Files API so quando ha documento.
 *
 * <p>Retry igual ao @with_retry: 3 tentativas, esperas de 1 s e 2 s, so em conexao/timeout e 529. O retry interno do
 * SDK fica desligado (maxRetries 0): no Django ele somava ate 2 retentativas por tentativa (ate 9 chamadas pagas).
 * Timeout de 30 s por tentativa. Sem chave valida: modo mock so no perfil test; fora dele, {@link ErroDeGeracao}
 * (nunca uma oracao de exemplo para cliente). A chave so vai no header x-api-key: nunca em log nem em mensagem.
 */
@Component
public class ClienteClaude {
    private static final Logger LOG = LoggerFactory.getLogger(ClienteClaude.class);
    static final String MODELO = "claude-sonnet-4-6";
    static final long MAX_TOKENS = 1500;
    static final String BETA_FILES = "files-api-2025-04-14";
    private static final Set<String> CHAVES_MOCK = Set.of("", "test_api_key_for_local_development");
    private static final CacheControlEphemeral EFEMERO = CacheControlEphemeral.builder().build();

    static final String ORACAO_MOCK = """
            Senhor, venho diante de Ti em nome de {nome}.

            Tu conheces cada detalhe da vida dela, cada angústia e cada esperança que habita seu coração. Neste momento, ela clama por Tua intervenção, e eu sei que Tu ouves.

            Pai, derrama Tua paz sobre {nome}. Que ela sinta Tua presença de forma real e tangível. Onde há medo, coloca coragem. Onde há dúvida, firma a fé. Onde há dor, traz o bálsamo do Teu amor.

            Guia seus passos, ilumina seu caminho e dá-lhe sabedoria para cada decisão. Que ela encontre descanso em Ti, sabendo que Tu és fiel para cumprir Tuas promessas.

            Em nome de Jesus, amém.

            ---
            [MODO DESENVOLVIMENTO: Esta é uma oração de exemplo. Configure ANTHROPIC_API_KEY com créditos para gerar orações personalizadas via IA.]""";

    private final String chave;
    private final boolean mockPermitido;
    private final PromptAtivo prompts;
    private final DocumentoRepository documentos;
    private final Consumer<Duration> dormir;
    private final AnthropicClient cliente;

    @Autowired
    public ClienteClaude(@Value("${ANTHROPIC_API_KEY:}") String chave,
            @Value("${clama.anthropic.url:https://api.anthropic.com}") String url, Environment env, PromptAtivo prompts,
            DocumentoRepository documentos) {
        this(chave, url, env.acceptsProfiles(Profiles.of("test")), prompts, documentos, Duration.ofSeconds(30),
                Retentativa::dormir);
        if (!mockPermitido && this.cliente == null) {
            LOG.error("ANTHROPIC_API_KEY ausente: toda geracao de oracao vai falhar (event=anthropic_key_missing)");
            SentryConfig.capturar("origem", "anthropic_key_ausente", null);
        }
    }

    ClienteClaude(String chave, String url, boolean mockPermitido, PromptAtivo prompts, DocumentoRepository documentos,
            Duration timeout, Consumer<Duration> dormir) {
        this.chave = chave == null ? "" : chave;
        this.mockPermitido = mockPermitido;
        this.prompts = prompts;
        this.documentos = documentos;
        this.dormir = dormir;
        // o SDK 3.x manda a chave como Authorization: Bearer e anthropic-version 2026-10-21; o Python do Django mandava
        // x-api-key e 2023-06-01 (gravado no contrato). Com x-api-key presente o SDK nao poe o Bearer.
        this.cliente = CHAVES_MOCK.contains(this.chave) ? null : AnthropicOkHttpClient.builder().apiKey(this.chave)
                .putHeader("x-api-key", this.chave).replaceHeaders("anthropic-version", "2023-06-01")
                .baseUrl(url.replaceAll("/+$", "")).maxRetries(0).timeout(timeout).build();
    }

    /**
     * O texto de content[0], cru (sem trim nem checagem de stop_reason). Sem prompt ativo ou com mais de um, sobem as
     * excecoes do {@link PromptAtivo}; 400 de credito vira {@link CreditosEsgotados}; o resto, {@link ErroDeGeracao}.
     */
    public String gerar(Pedido p) {
        if (cliente == null) {
            if (!mockPermitido) {
                throw new ErroDeGeracao("ANTHROPIC_API_KEY não configurada: geração de oração indisponível");
            }
            // ponytail: nome so de espaco cai em "irmã" (no Django seria IndexError); so existe no perfil test
            List<String> partes = p.getNome() == null ? List.of() : TextoPython.split(p.getNome());
            LOG.info("Retornando oração mock (event=anthropic_mock_prayer, pedido_id={})", p.getId());
            return ORACAO_MOCK.replace("{nome}", partes.isEmpty() ? "irmã" : partes.get(0));
        }
        PromptTemplate template = prompts.obter();
        List<String> fileIds = documentos.ativosSincronizados().stream().map(DocumentoContexto::getAnthropicFileId)
                .toList();
        if (!fileIds.isEmpty()) {
            LOG.info("Documentos de contexto incluídos (event=documentos_contexto_incluidos, count={}, file_ids={})",
                    fileIds.size(), fileIds);
        }
        return gerar(template.getSystemPrompt(), MontagemDoPrompt.mensagemDoUsuario(p, template), fileIds,
                p.getPlano().getComplexidade());
    }

    String gerar(String system, String mensagem, List<String> fileIds, String complexidade) {
        MessageCreateParams.Builder b = MessageCreateParams.builder().model(MODELO).maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system).cacheControl(EFEMERO).build()));
        if (fileIds.isEmpty()) {
            b.addUserMessage(mensagem);
        } else {
            List<ContentBlockParam> blocos = new ArrayList<>();
            for (String id : fileIds) {
                blocos.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(FileDocumentSource.builder().fileId(id).build()).cacheControl(EFEMERO).build()));
            }
            blocos.add(ContentBlockParam.ofText(TextBlockParam.builder().text(mensagem).build()));
            b.addUserMessageOfContentBlockParams(blocos).putAdditionalHeader("anthropic-beta", BETA_FILES);
        }
        MessageCreateParams params = b.build();
        AtomicInteger tentativa = new AtomicInteger();
        try {
            return Retentativa.executar(() -> chamar(params, complexidade, tentativa.incrementAndGet()), 3,
                    Retentativa.ESPERAS_PADRAO, ClienteClaude::retentavel, dormir);
        } catch (AnthropicIoException | AnthropicServiceException e) {
            // so os retentaveis chegam aqui, ja esgotados (conexao, timeout, 529)
            throw new ErroDeGeracao("Erro ao gerar oração: " + e.getMessage(), e);
        }
    }

    private static boolean retentavel(Throwable e) {
        return e instanceof AnthropicIoException || e instanceof AnthropicServiceException s && s.statusCode() == 529;
    }

    private String chamar(MessageCreateParams params, String complexidade, int tentativa) {
        long inicio = System.nanoTime();
        try {
            Message m = cliente.messages().create(params);
            String texto = m.content().get(0).asText().text();
            log(complexidade, tentativa, inicio, m.usage().outputTokens(),
                    m.usage().cacheCreationInputTokens().orElse(null), m.usage().cacheReadInputTokens().orElse(null),
                    null);
            return texto;
        } catch (AnthropicIoException e) {
            log(complexidade, tentativa, inicio, null, null, null, e.toString());
            throw e;
        } catch (AnthropicServiceException e) {
            log(complexidade, tentativa, inicio, null, null, null, "HTTP " + e.statusCode() + ": " + e.getMessage());
            if (e.statusCode() == 529) {
                throw e;
            }
            if (e.statusCode() == 400 && e.getMessage().toLowerCase(Locale.ROOT).contains("credit balance")) {
                LOG.warn("Anthropic sem créditos (event=anthropic_no_credits)");
                throw new CreditosEsgotados("Anthropic sem créditos: " + e.getMessage(), e);
            }
            throw new ErroDeGeracao("Erro ao gerar oração: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // content vazio ou 1o bloco nao-texto (IndexError/AttributeError no Django)
            log(complexidade, tentativa, inicio, null, null, null, e.toString());
            throw new ErroDeGeracao("Erro inesperado ao gerar oração: " + e, e);
        }
    }

    /** event=anthropic_request, sem PII: o input_tokens nao e logado, como no Django. */
    private static void log(String plano, int tentativa, long inicio, Long tokens, Long cacheCriacao, Long cacheLeitura,
            String erro) {
        double ms = Math.round((System.nanoTime() - inicio) / 10_000.0) / 100.0;
        if (erro != null) {
            LOG.warn("Anthropic request failed (event=anthropic_request, model={}, plano={}, attempt={}, duration_ms={}, "
                    + "error={})", MODELO, plano, tentativa, ms, erro);
        } else {
            LOG.info("Anthropic request completed (event=anthropic_request, model={}, plano={}, attempt={}, "
                    + "duration_ms={}, tokens_used={}, cache_creation_tokens={}, cache_read_tokens={})", MODELO, plano,
                    tentativa, ms, tokens, cacheCriacao, cacheLeitura);
        }
    }
}
