package me.clama.infra.fila;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerOverrides;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter;
import com.github.kagkarlsson.scheduler.serializer.Jackson3Serializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** O starter do db-scheduler (propriedades {@code db-scheduler.*}) monta o scheduler; aqui so o que muda. */
@Configuration(proxyBeanMethods = false)
class FilaConfig {

	/** Payload em JSON legivel em vez da serializacao Java do starter. */
	@Bean
	DbSchedulerOverrides filaOverrides() {
		return DbSchedulerOverrides.builder().serializer(new Jackson3Serializer()).build();
	}

	/**
	 * Testes: o scheduler existe (e enfileira), mas nao faz polling. Contextos em cache dividem {@code scheduled_tasks}
	 * e um roubaria o job do outro; quem precisa executar liga com {@code clama.fila.iniciar=true}.
	 */
	@Bean
	@ConditionalOnProperty(name = "clama.fila.iniciar", havingValue = "false")
	DbSchedulerStarter filaParada() {
		return () -> {};
	}
}
