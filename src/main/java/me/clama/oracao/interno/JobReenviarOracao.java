package me.clama.oracao.interno;

import java.time.Duration;
import java.util.UUID;
import me.clama.infra.fila.TipoDeJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code reenviar-oracao/<pedido>}: o reenvio do admin. Cada pedido de reenvio tem o seu {@code envio} (aleatorio) e a
 * chave {@code oracao/<id>/reenvio/<envio>}, estavel nas retentativas. Mesmas esperas do envio; esgotado em ENVIADA so
 * alerta (o status nao regride).
 */
@Component
class JobReenviarOracao extends TipoDeJob<JobReenviarOracao.Reenvio> {

	/** Payload so com ids. */
	record Reenvio(UUID pedido, UUID envio) {}

	private final JobEnviarOracao enviar;

	JobReenviarOracao(JobEnviarOracao enviar, @Value("${clama.oracao.espera-envio:30s}") Duration espera) {
		super("reenviar-oracao", Reenvio.class, JobEnviarOracao.politica(espera));
		this.enviar = enviar;
	}

	@Override
	protected void executar(Reenvio r, int tentativa) {
		enviar.enviar(r.pedido(), "oracao/" + r.pedido() + "/reenvio/" + r.envio(), true);
	}

	@Override
	protected void aoDesistir(Reenvio r, Throwable erro) {
		enviar.desistiu(r.pedido());
	}
}
