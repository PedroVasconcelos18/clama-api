package me.clama.pedido.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.catalogo.PlanoResumo;
import me.clama.infra.antibot.Turnstile;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.validacao.EmailNaoDescartavelValidator;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.NovoPedido;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.interno.CriacaoDePedido;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Criar pedido (pago, gratuito, doacao anonima), status publico e "meus pedidos". Limites de taxa: INV:23. */
@RestController
class PedidosController {

	static final String CAPTCHA = "Verificação anti-robô falhou. Atualize a página e tente de novo.";
	static final String DESCARTAVEL = "Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.";
	static final String NAO_ENCONTRADO = "Não encontramos esse pedido. Pode tentar novamente?";
	static final String MENSAGEM_CREDITOS = "Recebemos seu pedido com carinho. Sua oração será preparada e enviada em até 24 horas."
			+ " Se precisar falar com alguém antes, escreva pra contato@clama.me.";
	private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
	// ponytail: sentinelas no lugar de "sem filtro"; dentro do intervalo do timestamptz
	private static final Instant SEM_INICIO = Instant.parse("0001-01-01T00:00:00Z");
	private static final Instant SEM_FIM = Instant.parse("9999-12-31T00:00:00Z");

	private final CriacaoDePedido criacao;
	private final PedidoRepository pedidos;
	private final CatalogoApi catalogo;
	private final LimiteDeTaxa limite;
	private final Turnstile turnstile;

	PedidosController(CriacaoDePedido criacao, PedidoRepository pedidos, CatalogoApi catalogo, LimiteDeTaxa limite, Turnstile turnstile) {
		this.criacao = criacao;
		this.pedidos = pedidos;
		this.catalogo = catalogo;
		this.limite = limite;
		this.turnstile = turnstile;
	}

	@PostMapping("/api/pedidos")
	@ResponseStatus(HttpStatus.CREATED)
	PedidoCriado criar(@Valid @RequestBody NovoPedido n, Authentication auth, HttpServletRequest req) {
		var usuario = Long.valueOf(auth.getName());
		limitarCriacao(usuario);
		return PedidoCriado.de(criacao.criarPago(usuario, n, ClienteIp.de(req)));
	}

	@PostMapping("/api/pedidos/gratuito")
	@ResponseStatus(HttpStatus.CREATED)
	PedidoCriado criarGratuito(@Valid @RequestBody NovoPedido n, Authentication auth, HttpServletRequest req) {
		var usuario = Long.valueOf(auth.getName());
		limitarCriacao(usuario);
		return PedidoCriado.de(criacao.criarGratuito(usuario, n, ClienteIp.de(req)));
	}

	/** Ordem: campos (400, pelo @Valid), captcha fora de transacao, e-mail descartavel, e so entao plano e valor. */
	@PostMapping("/api/doacoes")
	@ResponseStatus(HttpStatus.CREATED)
	PedidoCriado doar(@Valid @RequestBody NovoPedido n, HttpServletRequest req) {
		var ip = ClienteIp.de(req);
		limite.exigir(new Cota("doacao:ip:" + ip, 10, Duration.ofHours(1)));
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
		return PedidoCriado.de(criacao.criarDoacao(n, ip));
	}

	/** O uuid e o segredo: sem checar dono. 120/min por IP porque o front consulta a cada 5 s ate o pagamento. */
	@GetMapping("/api/pedidos/{id}")
	StatusPublico status(@PathVariable String id, HttpServletRequest req) {
		limite.exigir(new Cota("pedido-status:ip:" + ClienteIp.de(req), 120, Duration.ofMinutes(1)));
		var p = uuid(id).flatMap(pedidos::status).orElseThrow(PedidosController::naoEncontrado);
		var plano = catalogo.planos(List.of(p.planoId())).get(p.planoId());
		var creditos = p.status() == StatusPedido.ERRO && "credit_balance".equals(p.ultimoErro());
		var oracao = p.status() == StatusPedido.ENVIADA ? pedidos.oracaoDe(p.id()).orElse(null) : null; // so em ENVIADA decifra
		return new StatusPublico(p.id(), p.status(), plano == null ? null : plano.nome(), p.instituicaoId(),
				p.valorCentavos(), p.criadoEm(), creditos ? MENSAGEM_CREDITOS : null, oracao);
	}

	/** Pedidos do logado, mais recentes primeiro; {@code from} e {@code to} sao dias (AAAA-MM-DD) em Sao Paulo, inclusivos. */
	@GetMapping("/api/conta/pedidos")
	List<PedidoDaConta> daConta(Authentication auth, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to) {
		var dia1 = dia(from);
		var dia2 = dia(to);
		var inicio = dia1 == null ? SEM_INICIO : dia1.atStartOfDay(FUSO).toInstant();
		var fim = dia2 == null ? SEM_FIM : dia2.plusDays(1).atStartOfDay(FUSO).toInstant().minusNanos(1000); // o banco guarda micro
		var lista = pedidos.findByUsuarioIdAndCriadoEmBetweenOrderByCriadoEmDesc(Long.valueOf(auth.getName()), inicio, fim);
		var planos = catalogo.planos(lista.stream().map(Pedido::getPlanoId).distinct().toList());
		return lista.stream().map(p -> {
			PlanoResumo plano = planos.get(p.getPlanoId());
			return new PedidoDaConta(p.getId(), p.getStatus(), plano == null ? null : plano.nome(), p.getValorCentavos(),
					p.isGratuito(), p.getCriadoEm(), oracaoVisivel(p));
		}).toList();
	}

	/** 10/min e 5/h por usuario (a conta e um e-mail so: o id faz o papel da chave por e-mail do INV:23). */
	private void limitarCriacao(Long usuario) {
		limite.exigir(new Cota("pedido:minuto:" + usuario, 10, Duration.ofMinutes(1)),
				new Cota("pedido:hora:" + usuario, 5, Duration.ofHours(1)));
	}

	private static String oracaoVisivel(Pedido p) {
		return p.getStatus() == StatusPedido.ENVIADA ? p.getOracao() : null;
	}

	private static java.util.Optional<UUID> uuid(String s) {
		if (s.length() != 36) return java.util.Optional.empty(); // fromString aceita formas curtas
		try {
			return java.util.Optional.of(UUID.fromString(s));
		} catch (IllegalArgumentException e) {
			return java.util.Optional.empty();
		}
	}

	/** Dia invalido e ignorado em silencio (como no sistema antigo). */
	private static LocalDate dia(String v) {
		if (v == null) return null;
		try {
			return LocalDate.parse(v);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static ErroDeNegocio naoEncontrado() {
		return new ErroDeNegocio(HttpStatus.NOT_FOUND, "pedido_nao_encontrado", "Pedido não encontrado.", NAO_ENCONTRADO);
	}
}
