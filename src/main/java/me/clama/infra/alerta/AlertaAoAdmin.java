package me.clama.infra.alerta;

import java.util.Map;
import java.util.UUID;
import me.clama.infra.email.EnviadorDeEmail;
import me.clama.infra.formato.Dinheiro;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.email.ModelosDeEmail;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

/**
 * E-mail de alerta ao admin (D11): pagamento com valor divergente, orfao, estorno de repasse ja transferido; na 4b, as
 * falhas da oracao. <b>Sem PII</b>: so id, status, motivo, plano, valor e o link do admin (e-mail e canal sem controle de
 * acesso; quem precisa do resto abre a ficha). Sai pelo {@link EnvioAssincrono}: falha de envio nunca muda a resposta.
 */
@Component
@EnableConfigurationProperties(AlertaAoAdmin.Props.class)
public class AlertaAoAdmin {

	/** {@code clama.alerta.*}. */
	@ConfigurationProperties("clama.alerta")
	public record Props(@DefaultValue("contato@clama.me") String emailAdmin,
			@DefaultValue("http://localhost:5173") String frontendUrl) {}

	/**
	 * {@code motivo}: codigo curto ({@code valor_divergente}...). {@code detalhe}: uma frase para quem le, sem PII
	 * (ids e valores, nunca nome, e-mail ou texto do pedido). Os demais podem ser nulos.
	 */
	public record Alerta(String motivo, String detalhe, UUID pedidoId, String status, String plano, Long valorCentavos) {}

	private final ModelosDeEmail modelos;
	private final EnviadorDeEmail enviador;
	private final EnvioAssincrono envio;
	private final Props props;

	AlertaAoAdmin(ModelosDeEmail modelos, EnviadorDeEmail enviador, EnvioAssincrono envio, Props props) {
		this.modelos = modelos;
		this.enviador = enviador;
		this.envio = envio;
		this.props = props;
	}

	/** {@code chave}: o mesmo alerta logico repetido em 24 h nao sai de novo (idempotencia da Resend). */
	public void enviar(Alerta a, String chave) {
		var id = a.pedidoId() == null ? null : a.pedidoId().toString(); // alerta sem pedido (evento expirado): sem "null"
		var base = props.frontendUrl().replaceAll("/+$", "");
		var email = modelos.montar(props.emailAdmin(), "[Clama] Alerta " + a.motivo() + (id == null ? "" : " — pedido " + id.substring(0, 8)),
				"alerta_admin", Map.of(
						"motivo", a.motivo(),
						"detalhe", ou(a.detalhe()),
						"pedido_id", ou(id),
						"status", ou(a.status()),
						"plano", ou(a.plano()),
						"valor", a.valorCentavos() == null ? "-" : Dinheiro.reais(a.valorCentavos()),
						"link", base + (id == null ? "/admin" : "/admin/pedidos?id=" + id)));
		envio.executar("alerta ao admin (" + a.motivo() + ")", () -> enviador.enviar(email, "alerta/" + chave));
	}

	private static String ou(String s) {
		return s == null || s.isBlank() ? "-" : s;
	}
}
