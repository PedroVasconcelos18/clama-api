package me.clama.freemium.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import me.clama.freemium.interno.PedidoFreemium;
import me.clama.infra.antibot.Turnstile;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.validacao.EmailNaoDescartavelValidator;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.NovoPedido;
import me.clama.pedido.StatusPedido;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Oracao gratuita anonima. Ordem (D6): campos (@Valid), limite 5/h por IP, Turnstile, e-mail descartavel e os portoes de banco. */
@RestController
class FreemiumController {

	static final String CAPTCHA = "Verificação anti-robô falhou. Atualize a página e tente de novo.";
	static final String DESCARTAVEL = "Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.";

	private final PedidoFreemium freemium;
	private final LimiteDeTaxa limite;
	private final Turnstile turnstile;

	FreemiumController(PedidoFreemium freemium, LimiteDeTaxa limite, Turnstile turnstile) {
		this.freemium = freemium;
		this.limite = limite;
		this.turnstile = turnstile;
	}

	@PostMapping("/api/freemium/pedidos")
	@ResponseStatus(HttpStatus.CREATED)
	PedidoFreemiumCriado submeter(@Valid @RequestBody NovoPedido n, HttpServletRequest req) {
		var ip = ClienteIp.de(req);
		limite.exigir(new Cota("freemium:ip:" + ip, 5, Duration.ofHours(1)));
		if (n.turnstileToken() == null || n.turnstileToken().isBlank()) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", null,
					List.of(new ErroDeCampo("turnstileToken", CAPTCHA)));
		}
		if (!turnstile.verificar(n.turnstileToken(), ip)) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "captcha_invalido", "Token Turnstile inválido", CAPTCHA);
		}
		if (!new EmailNaoDescartavelValidator().isValid(n.email(), null)) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "email_descartavel", "E-mail descartável não permitido", DESCARTAVEL);
		}
		return new PedidoFreemiumCriado(freemium.submeter(n, ip), StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
	}
}
