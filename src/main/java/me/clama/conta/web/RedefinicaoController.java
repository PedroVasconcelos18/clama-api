package me.clama.conta.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import me.clama.conta.RedefinicaoDeSenha;
import me.clama.infra.seguranca.ClienteIp;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonimos (com CSRF): pedir o link e usar o link. A redefinicao serve tambem ao link de "definir senha". */
@RestController
@RequestMapping("/api/auth")
class RedefinicaoController {

	record Pedido(@NotBlank @Email @Size(max = 254) String email) {}

	record Redefinicao(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 1024) String novaSenha) {}

	private final RedefinicaoDeSenha redefinicao;

	RedefinicaoController(RedefinicaoDeSenha redefinicao) {
		this.redefinicao = redefinicao;
	}

	@PostMapping("/esqueci-senha")
	Map<String, String> esqueci(@Valid @RequestBody Pedido pedido, HttpServletRequest req) {
		redefinicao.pedir(pedido.email(), ClienteIp.de(req));
		return Map.of("mensagemPastoral", RedefinicaoDeSenha.PASTORAL_ESQUECI);
	}

	@PostMapping("/redefinicao-de-senha")
	Map<String, String> redefinir(@Valid @RequestBody Redefinicao corpo) {
		redefinicao.redefinir(corpo.token(), corpo.novaSenha());
		return Map.of("mensagemPastoral", RedefinicaoDeSenha.PASTORAL_REDEFINIDA);
	}
}
