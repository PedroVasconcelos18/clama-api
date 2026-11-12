package me.clama.oracao.interno;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code clama.oracao.*}. Chave vazia nao impede o boot: a geracao falha com erro claro. {@code anthropicUrl} muda so nos
 * testes (WireMock). {@code timeout} e por tentativa.
 */
@ConfigurationProperties("clama.oracao")
public record OracaoProps(@DefaultValue("") String anthropicApiKey, @DefaultValue("https://api.anthropic.com") String anthropicUrl,
		@DefaultValue("claude-sonnet-4-6") String modelo, @DefaultValue("1500") long maxTokens, @DefaultValue("30s") Duration timeout,
		java.util.Map<String, PrecoPorMilhao> precos) {

	/** USD por milhao de tokens de um modelo ({@code clama.oracao.precos.<modelo>.*}). Qualquer um faltando = custo estimado nulo. */
	public record PrecoPorMilhao(BigDecimal entrada, BigDecimal saida, BigDecimal cacheEscrita, BigDecimal cacheLeitura) {
		public boolean completo() {
			return entrada != null && saida != null && cacheEscrita != null && cacheLeitura != null;
		}
	}


	/** A chave nunca aparece (o toString do record imprimiria todos os campos). */
	@Override
	public String toString() {
		return "OracaoProps[anthropicUrl=" + anthropicUrl + ", modelo=" + modelo + ", maxTokens=" + maxTokens + ", timeout=" + timeout + ", precos=" + precos + "]";
	}
}
