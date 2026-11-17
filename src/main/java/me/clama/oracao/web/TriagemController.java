package me.clama.oracao.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.oracao.interno.Triagem;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triagem do clamor na tela, antes do pedido existir: so responde, nao grava nada (a do pedido e o job
 * {@code triar-pedido}). {@code risco} nulo = a triagem falhou, e a tela mostra o CVV do mesmo jeito. 20 por hora por IP.
 */
@RestController
class TriagemController {

	record Clamor(@NotBlank @Size(max = 2000) String texto) {}

	record ResultadoDaTriagem(@Nullable Boolean risco) {}

	private final Triagem triagem;
	private final LimiteDeTaxa limite;

	TriagemController(Triagem triagem, LimiteDeTaxa limite) {
		this.triagem = triagem;
		this.limite = limite;
	}

	@PostMapping("/api/triagem")
	ResultadoDaTriagem triar(@Valid @RequestBody Clamor corpo, HttpServletRequest req) {
		limite.exigir(new Cota("triagem:ip:" + ClienteIp.de(req), 20, Duration.ofHours(1)));
		return new ResultadoDaTriagem(triagem.avaliar(corpo.texto()).orElse(null));
	}
}
