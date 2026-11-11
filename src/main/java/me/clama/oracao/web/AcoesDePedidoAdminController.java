package me.clama.oracao.web;

import java.time.Instant;
import java.util.UUID;
import me.clama.oracao.interno.AcoesDoAdminSobreOracao;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.StatusPedido;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gratuidade, reenvio e regeneracao do admin (D10; ADMIN e CSRF pela cadeia padrao). Resposta: o estado novo do pedido. */
@RestController
@RequestMapping("/api/admin/pedidos/{id}")
class AcoesDePedidoAdminController {

	/** {@code jaEmAndamento}: so o reenvio, quando a geracao ou o envio ainda esta na fila e ele nada fez. */
	record EstadoDoPedido(UUID id, StatusPedido status, boolean gratuito, long valorCentavos, Instant atualizadoEm,
			@Nullable String ultimoErro, boolean jaEmAndamento) {

		static EstadoDoPedido de(AcoesDoAdminSobreOracao.Resultado r) {
			var p = r.pedido();
			return new EstadoDoPedido(p.id(), p.status(), p.gratuito(), p.valorCentavos(), p.atualizadoEm(), p.ultimoErro(),
					r.jaEmAndamento());
		}
	}

	private final AcoesDoAdminSobreOracao acoes;

	AcoesDePedidoAdminController(AcoesDoAdminSobreOracao acoes) {
		this.acoes = acoes;
	}

	@PostMapping("/gratuidade")
	EstadoDoPedido gratuidade(@PathVariable String id, Authentication auth) {
		return EstadoDoPedido.de(acoes.conceder(autor(auth), uuid(id)));
	}

	@PostMapping("/reenvio")
	EstadoDoPedido reenvio(@PathVariable String id, Authentication auth) {
		return EstadoDoPedido.de(acoes.reenviar(autor(auth), uuid(id)));
	}

	@PostMapping("/regeneracao")
	EstadoDoPedido regeneracao(@PathVariable String id, Authentication auth) {
		return EstadoDoPedido.de(acoes.regenerar(autor(auth), uuid(id)));
	}

	private static Long autor(Authentication auth) {
		return Long.valueOf(auth.getName());
	}

	private static UUID uuid(String s) {
		try {
			if (s.length() == 36) return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			// cai no 404
		}
		throw new PedidoNaoEncontradoException();
	}
}
