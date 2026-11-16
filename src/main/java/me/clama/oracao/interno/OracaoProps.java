package me.clama.oracao.interno;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code clama.oracao.*}. Chave vazia nao impede o boot: a geracao falha com erro claro. {@code anthropicUrl} muda so nos
 * testes (WireMock). {@code timeout} e por tentativa. {@code retentativasDoSdk}: as do proprio SDK (408, 409, 429, 5xx, rede),
 * 2 como o default do SDK Python que o Django usa em producao; os testes desligam (0) para contar chamadas.
 */
@ConfigurationProperties("clama.oracao")
public record OracaoProps(@DefaultValue("") String anthropicApiKey, @DefaultValue("https://api.anthropic.com") String anthropicUrl,
		@DefaultValue("claude-sonnet-4-6") String modelo, @DefaultValue("1500") long maxTokens, @DefaultValue("30s") Duration timeout,
		java.util.Map<String, PrecoPorMilhao> precos, @DefaultValue("2") int retentativasDoSdk) {

	/** USD por milhao de tokens de um modelo ({@code clama.oracao.precos.<modelo>.*}). Qualquer um faltando = custo estimado nulo. */
	public record PrecoPorMilhao(BigDecimal entrada, BigDecimal saida, BigDecimal cacheEscrita, BigDecimal cacheLeitura) {
		public boolean completo() {
			return entrada != null && saida != null && cacheEscrita != null && cacheLeitura != null;
		}
	}


	/** A chave nunca aparece (o toString do record imprimiria todos os campos). */
	@Override
	public String toString() {
		return "OracaoProps[anthropicUrl=" + anthropicUrl + ", modelo=" + modelo + ", maxTokens=" + maxTokens + ", timeout=" + timeout + ", precos=" + precos
				+ ", retentativasDoSdk=" + retentativasDoSdk + "]";
	}
}
