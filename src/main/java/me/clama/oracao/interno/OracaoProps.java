package me.clama.oracao.interno;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code clama.oracao.*}. Chave vazia nao impede o boot: a geracao falha com erro claro. {@code anthropicUrl} muda so nos
 * testes (WireMock). {@code timeout} e por tentativa.
 */
@ConfigurationProperties("clama.oracao")
public record OracaoProps(@DefaultValue("") String anthropicApiKey, @DefaultValue("https://api.anthropic.com") String anthropicUrl,
		@DefaultValue("claude-sonnet-4-6") String modelo, @DefaultValue("1500") long maxTokens, @DefaultValue("30s") Duration timeout) {

	/** A chave nunca aparece (o toString do record imprimiria todos os campos). */
	@Override
	public String toString() {
		return "OracaoProps[anthropicUrl=" + anthropicUrl + ", modelo=" + modelo + ", maxTokens=" + maxTokens + ", timeout=" + timeout + "]";
	}
}
