package me.clama.oracao.interno;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Triagem de crise: a Claude le o texto do pedido e responde {@code RISCO} ou {@code SEM_RISCO} (system em
 * {@code oracao/triagem.txt}). Qualquer outra resposta, ou falha, e vazio: quem chama decide (a tela mostra o CVV, o job
 * tenta de novo). Nem o texto nem a resposta vao para o log.
 */
@Component
@EnableConfigurationProperties(TriagemProps.class)
public class Triagem {

	private static final Logger LOG = LoggerFactory.getLogger(Triagem.class);

	private final ClienteClaude claude;
	private final TriagemProps props;
	private final String sistema;

	Triagem(ClienteClaude claude, TriagemProps props) {
		this.claude = claude;
		this.props = props;
		try {
			this.sistema = new ClassPathResource("oracao/triagem.txt").getContentAsString(StandardCharsets.UTF_8).strip();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** {@code true} = risco, {@code false} = sem risco (texto em branco nem chama a Claude), vazio = falhou. */
	public Optional<Boolean> avaliar(String texto) {
		if (texto == null || texto.isBlank()) return Optional.of(false);
		String resposta;
		try {
			resposta = claude.classificar(sistema, texto, props.modelo(), props.maxTokens(), props.timeout());
		} catch (RuntimeException e) {
			LOG.warn("triagem falhou (event=triagem, motivo={})", e.getClass().getSimpleName());
			return Optional.empty();
		}
		return switch (resposta.strip().toUpperCase(Locale.ROOT)) {
			case "RISCO" -> Optional.of(true);
			case "SEM_RISCO" -> Optional.of(false);
			default -> {
				LOG.warn("triagem falhou (event=triagem, motivo=resposta_fora_do_formato)");
				yield Optional.empty();
			}
		};
	}
}
