package me.clama.oracao.interno;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.catalogo.PlanoResumo;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.infra.fila.Fila;
import me.clama.infra.fila.TipoDeJob;
import me.clama.pedido.PedidoApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code alerta-admin/<pedido>} (D11): o alerta das falhas da oracao e duravel. Enfileirado <b>na mesma transacao</b>
 * que leva o pedido ao estado do alerta (so sai se ela comitar, e sobrevive a deploy). Payload so com codigos. Sem
 * retentativa da fila (a Resend ja tenta 3 vezes); um por pedido por hora: a chave {@code alerta/<id>/<hora UTC>} faz a
 * Resend descartar o repetido, e a instancia deterministica nao deixa dois na fila. Sem PII.
 */
@Component
class JobAlertaAdmin extends TipoDeJob<JobAlertaAdmin.Aviso> {

	/** {@code motivo}: codigo curto ({@code credit_balance}, {@code falha_geracao}, {@code falha_envio}...). */
	record Aviso(UUID pedido, String motivo) {}

	private final Fila fila;
	private final AlertaAoAdmin alerta;
	private final PedidoApi pedidos;
	private final CatalogoApi catalogo;
	private final AdminDeDocumentos documentos;
	private final Clock relogio;

	JobAlertaAdmin(@Lazy Fila fila, AlertaAoAdmin alerta, PedidoApi pedidos, CatalogoApi catalogo,
			AdminDeDocumentos documentos, Clock relogio, @Value("${clama.oracao.espera-alerta:2m}") Duration espera) {
		super("alerta-admin", Aviso.class, new Retentativas(5, espera, 2.0));
		this.fila = fila;
		this.alerta = alerta;
		this.pedidos = pedidos;
		this.catalogo = catalogo;
		this.documentos = documentos;
		this.relogio = relogio;
	}

	/** Exige a transacao da mudanca de status; ja na fila para este pedido = no-op. */
	void enfileirar(UUID id, String motivo) {
		fila.enfileirar(this, instancia(id + "/" + motivo), new Aviso(id, motivo), relogio.instant());
	}

	@Override
	protected void executar(Aviso a, int tentativa) {
		var id = a.pedido();
		var r = pedidos.resumo(id).orElse(null);
		PlanoResumo plano = r == null || r.planoId() == null ? null : catalogo.planos(List.of(r.planoId())).get(r.planoId());
		alerta.enviarAgora(new AlertaAoAdmin.Alerta(a.motivo(), detalhe(a.motivo()), id, r == null ? null : r.status().name(),
				plano == null ? null : plano.nome(), r == null ? null : r.valorCentavos()),
				id + "/" + a.motivo() + "/" + relogio.instant().truncatedTo(ChronoUnit.HOURS));
	}

	private String detalhe(String motivo) {
		return switch (motivo) {
			case "credit_balance" -> "A conta da Anthropic está sem créditos. Recarregue e regenere o pedido pelo admin.";
			case "falha_geracao" -> "A geração da oração falhou (recusa da Claude ou todas as tentativas esgotadas)."
					+ (documentos.fileIdsAtivos().isEmpty() ? ""
							: " Há documentos de contexto ativos: conferir documentos ativos (um arquivo pode ter sumido da Files API).");
			case "falha_envio" -> "O e-mail da oração falhou em todas as tentativas (Resend). A oração está gravada: use o reenvio.";
			case "falha_reenvio" -> "O reenvio da oração falhou em todas as tentativas (Resend). O pedido segue ENVIADA.";
			case "pedido_preso" -> "O pedido ficou mais de " + VarreduraDePedidosPresos.JANELA.toMinutes()
					+ " minutos parado sem job na fila. Nada foi regerado: confira e regenere ou reenvie pelo admin.";
			default -> null;
		};
	}
}
