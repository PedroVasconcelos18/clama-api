package me.clama.oracao.interno;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.core.MultipartField;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.NotFoundException;
import com.anthropic.models.files.FileUploadParams;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.FileDocumentSource;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * A Claude pelo SDK oficial ({@code anthropic-java}), {@code POST /v1/messages} nao beta. O SDK manda o
 * {@code anthropic-version} dele. A chave vai no {@code x-api-key}: o {@code apiKey()} do SDK 3.x manda
 * {@code Authorization: Bearer} e so deixa de mandar quando o {@code X-Api-Key} ja esta no request
 * ({@code StaticAuthHeaders}); por isso o header e posto a mao. Nunca em log nem em mensagem.
 *
 * <p>Retentativa nossa (a do SDK fica desligada, {@code maxRetries(0)}), paridade com o INV:106: 3 tentativas com esperas
 * de 1 e 2 s, so em rede/timeout e 529. Primeiro bloco sem texto (ou em branco) e {@link Falha}. Credito esgotado (400 "credit balance", ou 402) vira {@link CreditosEsgotados} sem retentar. O texto
 * e o do primeiro bloco, que tem de ser texto ({@code stop_reason=max_tokens} so gera WARN). O system vai com
 * {@code cache_control} efemero.
 *
 * <p>Falhas: {@link Falha} = pedido recusado ou resposta inutilizavel (502 na previa); {@link Indisponivel} = a Claude nao
 * atendeu (rede, 401/403, 429, 5xx, 529, sem chave; 503); {@link CreditosEsgotados} = conta sem credito (503). Qualquer
 * outra excecao do SDK vira {@link Falha}.
 */
@Component
@EnableConfigurationProperties(OracaoProps.class)
public class ClienteClaude {

	private static final Logger LOG = LoggerFactory.getLogger(ClienteClaude.class);
	static final int TENTATIVAS = 3;
	/** Files API: o upload de ate 32 MB nao cabe nos 30 s da geracao; so estas chamadas usam estes prazos. */
	static final Duration PRAZO_UPLOAD = Duration.ofMinutes(5);
	static final Duration PRAZO_ARQUIVO = Duration.ofSeconds(60);
	private static final CacheControlEphemeral EFEMERO = CacheControlEphemeral.builder().build();

	/** Resposta inutilizavel ou pedido recusado pela API; tentar de novo igual nao resolve. */
	public static class Falha extends RuntimeException {
		Falha(String mensagem, Throwable causa) {
			super(mensagem, causa);
		}
	}

	/** A Claude nao atendeu agora (rede, timeout, sobrecarga, 5xx, credencial ou chave ausente). */
	public static class Indisponivel extends Falha {
		Indisponivel(String mensagem, Throwable causa) {
			super(mensagem, causa);
		}
	}

	/** Conta da Anthropic sem credito: nao adianta retentar ate o admin recarregar. */
	public static final class CreditosEsgotados extends Indisponivel {
		CreditosEsgotados(Throwable causa) {
			super("Anthropic sem créditos", causa);
		}
	}

	private final OracaoProps props;
	private final Consumer<Duration> dormir;
	private final AnthropicClient cliente;

	/** Fora de local/teste, sem chave: ERROR no boot e Sentry, sem barrar o boot. */
	@Autowired
	ClienteClaude(OracaoProps props, Environment env) {
		this(props, ClienteClaude::dormir);
		if (cliente == null && !env.acceptsProfiles(Profiles.of("local", "test"))) {
			LOG.error("ANTHROPIC_API_KEY ausente: toda geracao de oracao vai falhar ate configurar");
			Sentry.captureMessage("ANTHROPIC_API_KEY ausente: geracao de oracao indisponivel", SentryLevel.ERROR);
		}
	}

	ClienteClaude(OracaoProps props, Consumer<Duration> dormir) {
		this.props = props;
		this.dormir = dormir;
		var chave = props.anthropicApiKey().strip();
		this.cliente = chave.isEmpty() ? null
				: AnthropicOkHttpClient.builder().apiKey(chave).putHeader("X-Api-Key", chave)
						.baseUrl(props.anthropicUrl().replaceAll("/+$", "")).maxRetries(0).timeout(props.timeout()).build();
	}

	@PreDestroy
	void fechar() {
		if (cliente != null) cliente.close();
	}

	/** Geracao do pedido, com a retentativa. {@code mensagem} tem PII: nunca vai para o log. */
	public String gerar(String sistema, String mensagem) {
		return gerar(sistema, mensagem, List.of());
	}

	/**
	 * Com os documentos de contexto ativos ({@code file_id} da Files API, em ordem estavel): um bloco {@code document}
	 * por arquivo antes do texto, e um unico {@code cache_control} no ultimo (com o do system sao 2 pontos, o limite da
	 * API e 4). A Files API saiu do beta no SDK 3.x: nenhum header beta.
	 */
	public String gerar(String sistema, String mensagem, List<String> fileIds) {
		return chamar(sistema, mensagem, fileIds, TENTATIVAS);
	}

	/** Previa do admin: uma chamada so (o admin espera na tela). */
	public String previa(String sistema, String mensagem) {
		return chamar(sistema, mensagem, List.of(), 1);
	}

	private String chamar(String sistema, String mensagem, List<String> fileIds, int tentativas) {
		if (cliente == null) {
			throw new Indisponivel("ANTHROPIC_API_KEY não configurada: geração de oração indisponível", null);
		}
		var builder = MessageCreateParams.builder().model(props.modelo()).maxTokens(props.maxTokens())
				.systemOfTextBlockParams(List.of(TextBlockParam.builder().text(sistema).cacheControl(EFEMERO).build()));
		if (fileIds.isEmpty()) {
			builder.addUserMessage(mensagem);
		} else {
			var blocos = new java.util.ArrayList<ContentBlockParam>();
			for (int i = 0; i < fileIds.size(); i++) {
				var doc = DocumentBlockParam.builder().source(FileDocumentSource.builder().fileId(fileIds.get(i)).build());
				if (i == fileIds.size() - 1) doc.cacheControl(EFEMERO);
				blocos.add(ContentBlockParam.ofDocument(doc.build()));
			}
			blocos.add(ContentBlockParam.ofText(TextBlockParam.builder().text(mensagem).build()));
			builder.addUserMessageOfContentBlockParams(blocos);
		}
		var params = builder.build();
		for (int tentativa = 1;; tentativa++) {
			var inicio = System.nanoTime();
			Indisponivel falha;
			boolean retentavel;
			try {
				return texto(cliente.messages().create(params), tentativa, inicio);
			} catch (Falha e) {
				throw e;
			} catch (AnthropicInvalidDataException e) {
				throw new Falha("Resposta da Claude fora do formato", e);
			} catch (AnthropicIoException e) {
				LOG.warn("Claude falhou (event=claude_request, model={}, error=io, attempt={}, duration_ms={})", props.modelo(),
						tentativa, ms(inicio));
				falha = new Indisponivel("Claude inacessível (rede ou timeout)", e);
				retentavel = true;
			} catch (AnthropicServiceException e) {
				var status = e.statusCode();
				LOG.warn("Claude falhou (event=claude_request, model={}, status={}, type={}, attempt={}, duration_ms={})", props.modelo(),
						status, e.errorType().map(Object::toString).orElse(null), tentativa, ms(inicio));
				if (semCredito(e)) throw new CreditosEsgotados(e);
				if (status == 400 || status == 404 || status == 413 || status == 422) {
					throw new Falha("Claude recusou o pedido (HTTP " + status + ")", e);
				}
				falha = new Indisponivel("Claude indisponível (HTTP " + status + ")", e);
				retentavel = status == 529;
			} catch (RuntimeException e) { // AnthropicException e o que mais o SDK lancar: nunca sai sem classificar
				LOG.warn("Claude falhou (event=claude_request, model={}, error={}, attempt={}, duration_ms={})", props.modelo(),
						e.getClass().getSimpleName(), tentativa, ms(inicio));
				throw new Falha("Erro inesperado do SDK da Claude", e);
			}
			if (!retentavel || tentativa == tentativas) throw falha;
			dormir.accept(Duration.ofSeconds(1L << (tentativa - 1)));
		}
	}

	/** Sobe o arquivo (lido em fluxo) para a Files API e devolve o {@code file_id}. Uma tentativa so: o admin espera na tela. */
	public String enviarArquivo(Path arquivo, String nome, String mime) {
		try {
			return arquivos("upload", () -> {
				try (InputStream in = Files.newInputStream(arquivo)) {
					var parte = MultipartField.<InputStream>builder().value(in).filename(nome).contentType(mime).build();
					return cliente.files().upload(FileUploadParams.builder().file(parte).build(), prazo(PRAZO_UPLOAD)).id();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (NaoExiste e) {
			throw new Falha("Files API respondeu 404 ao enviar", null);
		}
	}

	/** Apaga na Files API; 404 (ja nao existe) conta como apagado. */
	public void apagarArquivo(String fileId) {
		try {
			arquivos("delete", () -> cliente.files().delete(fileId, prazo(PRAZO_ARQUIVO)));
		} catch (NaoExiste e) {
			// ja apagado la
		}
	}

	/** Metadados na Files API: {@code false} so no 404; qualquer outra falha lanca. */
	public boolean arquivoExiste(String fileId) {
		try {
			arquivos("metadata", () -> cliente.files().retrieveMetadata(fileId, prazo(PRAZO_ARQUIVO)));
			return true;
		} catch (NaoExiste e) {
			return false;
		}
	}

	private static RequestOptions prazo(Duration d) {
		return RequestOptions.builder().timeout(d).build();
	}

	private static final class NaoExiste extends RuntimeException {
		NaoExiste() {
			super(null, null, false, false);
		}
	}

	private <T> T arquivos(String operacao, Supplier<T> chamada) {
		if (cliente == null) {
			throw new Indisponivel("ANTHROPIC_API_KEY não configurada: Files API indisponível", null);
		}
		try {
			return chamada.get();
		} catch (NotFoundException e) {
			throw new NaoExiste();
		} catch (AnthropicIoException | UncheckedIOException e) {
			LOG.warn("Files API falhou (event=claude_files, op={}, error=io)", operacao);
			throw new Indisponivel("Files API inacessível (rede ou timeout)", e);
		} catch (AnthropicServiceException e) {
			LOG.warn("Files API falhou (event=claude_files, op={}, status={})", operacao, e.statusCode());
			var status = e.statusCode();
			if (status == 400 || status == 413 || status == 422) throw new Falha("Files API recusou o pedido (HTTP " + status + ")", e);
			throw new Indisponivel("Files API indisponível (HTTP " + status + ")", e);
		} catch (RuntimeException e) {
			LOG.warn("Files API falhou (event=claude_files, op={}, error={})", operacao, e.getClass().getSimpleName());
			throw new Falha("Erro inesperado do SDK na Files API", e);
		}
	}

	/** Primeiro bloco tem de ser texto; tokens e cache no log, sem PII (o input nao e logado). */
	private String texto(Message m, int tentativa, long inicio) {
		var u = m.usage();
		LOG.info("Claude ok (event=claude_request, model={}, attempt={}, duration_ms={}, stop_reason={}, output_tokens={}, "
				+ "cache_creation_tokens={}, cache_read_tokens={})", props.modelo(), tentativa, ms(inicio),
				m.stopReason().map(Object::toString).orElse(null), u.outputTokens(), u.cacheCreationInputTokens().orElse(null),
				u.cacheReadInputTokens().orElse(null));
		if (m.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
			LOG.warn("Claude parou no max_tokens: oracao possivelmente cortada (event=claude_max_tokens, model={}, max_tokens={})",
					props.modelo(), props.maxTokens());
		}
		if (m.content().isEmpty() || !m.content().get(0).isText()) {
			throw new Falha("Resposta da Claude sem texto no primeiro bloco", null);
		}
		var texto = m.content().get(0).asText().text();
		if (texto.isBlank()) throw new Falha("Resposta da Claude com texto vazio", null);
		return texto;
	}

	/** 402 (billing) ou o 400 "Your credit balance is too low...". */
	private static boolean semCredito(AnthropicServiceException e) {
		if (e.statusCode() == 402) return true;
		var msg = e.apiMessage().orElse(String.valueOf(e.getMessage()));
		return e.statusCode() == 400 && msg.toLowerCase(Locale.ROOT).contains("credit balance");
	}

	private static long ms(long inicio) {
		return (System.nanoTime() - inicio) / 1_000_000;
	}

	static void dormir(Duration d) {
		try {
			Thread.sleep(d);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
