package me.clama;

import me.clama.infra.cripto.Cifra;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A {@code Cifra} para {@code @DataJpaTest}, que nao varre {@code @Configuration}. Todo slice que carrega o Hibernate
 * precisa dela, porque o {@code Pedido} usa o conversor {@code CampoCifrado}, que o Hibernate pede ao Spring.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CriptoDeTeste {

	@Bean
	Cifra cifra(@Value("${clama.cripto.chave}") String chave) {
		return new Cifra(chave);
	}
}
