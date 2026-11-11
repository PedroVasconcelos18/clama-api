package me.clama.infra.cripto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CriptoProps.class)
class ConfigDeCripto {

	@Bean
	Cifra cifra(CriptoProps props) {
		return new Cifra(props.chave());
	}

	@Bean
	HmacDeIdentificador hmacDeIdentificador(CriptoProps props) {
		return new HmacDeIdentificador(props.chaveHmac());
	}
}
