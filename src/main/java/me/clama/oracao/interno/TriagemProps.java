package me.clama.oracao.interno;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code clama.triagem.*}: o modelo (padrao: o da oracao, no {@code application.yml}), o teto de tokens da resposta e o
 * prazo da chamada (uma tentativa so, sem as retentativas do SDK).
 */
@ConfigurationProperties("clama.triagem")
record TriagemProps(String modelo, long maxTokens, Duration timeout) {}
