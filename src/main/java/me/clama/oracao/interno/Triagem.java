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
 * {@code oracao/triagem.txt}; leitura tolerante, ver {@link #ler}). Qualquer outra resposta, ou falha, e vazio: quem chama decide (a tela mostra o CVV, o job
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
		var risco = ler(resposta);
		if (risco.isEmpty()) LOG.warn("triagem falhou (event=triagem, motivo=resposta_fora_do_formato)");
		return risco;
	}

	/**
	 * Tolerante: a Claude as vezes pontua, marca ({@code **RISCO**}) ou acrescenta uma frase. Vale a primeira palavra, em
	 * caixa alta e sem pontuacao, aspas ou asteriscos em volta; {@code SEM_RISCO} (ou {@code SEM RISCO}) e falso,
	 * {@code RISCO} e verdadeiro, o resto e vazio.
	 */
	private static Optional<Boolean> ler(String resposta) {
		var texto = resposta == null ? "" : resposta.strip().toUpperCase(Locale.ROOT).replaceFirst("^[^A-Z_]+", "");
		var primeira = texto.split("\\s+", 2)[0].replaceFirst("[^A-Z_]+$", "");
		if (primeira.startsWith("SEM_RISCO") || texto.startsWith("SEM RISCO")) return Optional.of(false);
		if (primeira.equals("RISCO")) return Optional.of(true);
		return Optional.empty();
	}
}
