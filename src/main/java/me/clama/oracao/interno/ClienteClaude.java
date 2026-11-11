package me.clama.oracao.interno;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * A Claude pelo SDK oficial ({@code anthropic-java}), {@code POST /v1/messages} nao beta. O SDK manda o
 * {@code anthropic-version} dele; a chave vai como o SDK manda (nunca em log nem em mensagem).
 *
 * <p>Retentativa nossa (a do SDK fica desligada, {@code maxRetries(0)}): 4 tentativas com esperas de 1, 2 e 4 s, so em
 * rede/timeout e 529. Credito esgotado (400 "credit balance", ou 402) vira {@link CreditosEsgotados} sem retentar. O texto
 * e o do primeiro bloco, que tem de ser texto. O system vai com {@code cache_control} efemero.
 *
 * <p>Falhas: {@link Falha} = pedido recusado ou resposta inutilizavel (502 na previa); {@link Indisponivel} = a Claude nao
 * atendeu (rede, 401/403, 429, 5xx, 529, sem chave; 503); {@link CreditosEsgotados} = conta sem credito (503).
 */
@Component
@EnableConfigurationProperties(OracaoProps.class)
public class ClienteClaude {

	private static final Logger LOG = LoggerFactory.getLogger(ClienteClaude.class);
	static final int TENTATIVAS = 4;
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
		this.cliente = props.anthropicApiKey().isBlank() ? null
				: AnthropicOkHttpClient.builder().apiKey(props.anthropicApiKey().strip())
						.baseUrl(props.anthropicUrl().replaceAll("/+$", "")).maxRetries(0).timeout(props.timeout()).build();
	}

	@PreDestroy
	void fechar() {
		if (cliente != null) cliente.close();
	}

	/** Geracao do pedido, com a retentativa. {@code mensagem} tem PII: nunca vai para o log. */
	public String gerar(String sistema, String mensagem) {
		return chamar(sistema, mensagem, TENTATIVAS);
	}

	/** Previa do admin: uma chamada so (o admin espera na tela). */
	public String previa(String sistema, String mensagem) {
		return chamar(sistema, mensagem, 1);
	}

	private String chamar(String sistema, String mensagem, int tentativas) {
		if (cliente == null) {
			throw new Indisponivel("ANTHROPIC_API_KEY não configurada: geração de oração indisponível", null);
		}
		var params = MessageCreateParams.builder().model(props.modelo()).maxTokens(props.maxTokens())
				.systemOfTextBlockParams(List.of(TextBlockParam.builder().text(sistema).cacheControl(EFEMERO).build()))
				.addUserMessage(mensagem).build();
		for (int tentativa = 1;; tentativa++) {
			var inicio = System.nanoTime();
			Indisponivel falha;
			boolean retentavel;
			try {
				return texto(cliente.messages().create(params), tentativa, inicio);
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
			}
			if (!retentavel || tentativa == tentativas) throw falha;
			dormir.accept(Duration.ofSeconds(1L << (tentativa - 1)));
		}
	}

	/** Primeiro bloco tem de ser texto; tokens e cache no log, sem PII (o input nao e logado). */
	private String texto(Message m, int tentativa, long inicio) {
		var u = m.usage();
		LOG.info("Claude ok (event=claude_request, model={}, attempt={}, duration_ms={}, stop_reason={}, output_tokens={}, "
				+ "cache_creation_tokens={}, cache_read_tokens={})", props.modelo(), tentativa, ms(inicio),
				m.stopReason().map(Object::toString).orElse(null), u.outputTokens(), u.cacheCreationInputTokens().orElse(null),
				u.cacheReadInputTokens().orElse(null));
		if (m.content().isEmpty() || !m.content().get(0).isText()) {
			throw new Falha("Resposta da Claude sem texto no primeiro bloco", null);
		}
		return m.content().get(0).asText().text();
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
