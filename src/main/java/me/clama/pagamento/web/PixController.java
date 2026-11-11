package me.clama.pagamento.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.pagamento.interno.Cobrancas;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gera (ou devolve o vigente) o Pix do pedido. Sem login: o uuid e o segredo, como no status publico. 10/min por IP (INV:90). */
@RestController
class PixController {

	private final Cobrancas cobrancas;
	private final LimiteDeTaxa limite;

	PixController(Cobrancas cobrancas, LimiteDeTaxa limite) {
		this.cobrancas = cobrancas;
		this.limite = limite;
	}

	@PostMapping("/api/pedidos/{id}/pix")
	PixDoPedido pix(@PathVariable String id, HttpServletRequest req) {
		limite.exigir(new Cota("pix:ip:" + ClienteIp.de(req), 10, Duration.ofMinutes(1)));
		return PixDoPedido.de(cobrancas.pix(uuid(id)));
	}

	/** Malformado e 404 (nao 400): para quem tem o link, e o mesmo que inexistente. */
	private static UUID uuid(String s) {
		try {
			if (s.length() == 36) return UUID.fromString(s); // fromString aceita formas curtas
		} catch (IllegalArgumentException e) {
			// cai no 404
		}
		throw Cobrancas.naoEncontrado();
	}
}
