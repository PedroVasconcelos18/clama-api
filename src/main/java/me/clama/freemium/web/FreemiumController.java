package me.clama.freemium.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import me.clama.conta.ContaApi;
import me.clama.freemium.interno.ConfirmacaoFreemium;
import me.clama.freemium.interno.PedidoFreemium;
import me.clama.infra.antibot.Turnstile;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.validacao.EmailNaoDescartavelValidator;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.NovoPedido;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Oracao gratuita anonima. Ordem (D6): campos (@Valid), limite 5/h por IP, Turnstile, e-mail descartavel e os portoes de banco. */
@RestController
class FreemiumController {

	private static final Logger LOG = LoggerFactory.getLogger(FreemiumController.class);

	static final String CAPTCHA = "Verificação anti-robô falhou. Atualize a página e tente de novo.";
	static final String DESCARTAVEL = "Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.";

	/** Corpo da confirmacao: o token tem 43 caracteres; o teto barra lixo antes do hash. */
	record Confirmar(@NotBlank @Size(max = 200) String token) {}

	private final PedidoFreemium freemium;
	private final ConfirmacaoFreemium confirmacao;
	private final LimiteDeTaxa limite;
	private final Turnstile turnstile;
	private final PedidoApi pedidos;
	private final ContaApi conta;

	FreemiumController(PedidoFreemium freemium, ConfirmacaoFreemium confirmacao, LimiteDeTaxa limite, Turnstile turnstile,
			PedidoApi pedidos, ContaApi conta) {
		this.pedidos = pedidos;
		this.conta = conta;
		this.freemium = freemium;
		this.confirmacao = confirmacao;
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

	/**
	 * Os dois tokens (o do link e o de senha devolvido) sao credenciais: nao entram em log nem em cache. 30/h por IP. O
	 * token de senha sai depois do commit da confirmacao: se a emissao falhar, a resposta segue sem ele (o pedido ja
	 * esta PAGO) e a pessoa usa o link do e-mail da oracao.
	 */
	@PostMapping("/api/freemium/confirmacao")
	ResponseEntity<PedidoConfirmado> confirmar(@Valid @RequestBody Confirmar corpo, HttpServletRequest req) {
		limite.exigir(new Cota("freemium-confirmacao:ip:" + ClienteIp.de(req), 30, Duration.ofHours(1)));
		var id = confirmacao.confirmar(corpo.token());
		String tokenDeSenha = null;
		try {
			var destino = pedidos.doadores(List.of(id)).get(id).email(); // nunca logar
			tokenDeSenha = pedidos.donoDe(id).flatMap(dono -> conta.tokenDeAtivacao(dono, destino)).orElse(null);
		} catch (RuntimeException e) {
			LOG.error("confirmacao: token de senha nao emitido, segue sem (pedido_id={})", id, e);
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PedidoConfirmado(id, StatusPedido.PAGO, tokenDeSenha));
	}
}
