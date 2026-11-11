package me.clama.infra.cripto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("clama.cripto")
record CriptoProps(String chave, String chaveHmac) {}
