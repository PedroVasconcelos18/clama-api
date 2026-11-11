package me.clama.oracao.interno;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.catalogo.PlanoResumo;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.pedido.PedidoApi;
import org.springframework.stereotype.Component;

/**
 * Alerta ao admin das falhas da oracao (D11), chamado <b>depois do commit</b> que levou o pedido ao estado do alerta. Um
 * por pedido por hora: a chave {@code alerta/<id>/<hora UTC truncada>} faz a Resend descartar o repetido, sem memoria
 * local e valendo entre instancias. Sem PII: id, status, plano e valor (o {@link AlertaAoAdmin} monta o resto).
 */
@Component
class AlertasDaOracao {

	private final AlertaAoAdmin alerta;
	private final PedidoApi pedidos;
	private final CatalogoApi catalogo;
	private final Clock relogio;

	AlertasDaOracao(AlertaAoAdmin alerta, PedidoApi pedidos, CatalogoApi catalogo, Clock relogio) {
		this.alerta = alerta;
		this.pedidos = pedidos;
		this.catalogo = catalogo;
		this.relogio = relogio;
	}

	/** Nunca lanca: alerta que falha so vai para o log e o Sentry. */
	void alertar(UUID id, String motivo, String detalhe) {
		try {
			var r = pedidos.resumo(id).orElse(null);
			PlanoResumo plano = r == null || r.planoId() == null ? null : catalogo.planos(List.of(r.planoId())).get(r.planoId());
			alerta.enviar(new AlertaAoAdmin.Alerta(motivo, detalhe, id, r == null ? null : r.status().name(),
					plano == null ? null : plano.nome(), r == null ? null : r.valorCentavos()),
					id + "/" + relogio.instant().truncatedTo(ChronoUnit.HOURS));
		} catch (RuntimeException e) {
			EnvioAssincrono.registrar("alerta ao admin (" + motivo + ") do pedido " + id, e);
		}
	}
}
