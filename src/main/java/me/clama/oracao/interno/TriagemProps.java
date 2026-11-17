package me.clama.oracao.interno;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code clama.triagem.*}: o modelo (padrao: o da oracao, no {@code application.yml}) e o teto de tokens da resposta. */
@ConfigurationProperties("clama.triagem")
record TriagemProps(String modelo, long maxTokens) {}
