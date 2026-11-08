package me.clama.infra.seguranca;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class RotasDeSegurancaParaTeste {

	@GetMapping("/admin/teste")
	String admin() {
		return "ok";
	}

	@PostMapping("/admin/teste")
	String adminPost() {
		return "ok";
	}

	@GetMapping("/publico/teste")
	String publico() {
		return "ok";
	}

	/** Simula o que um @PreAuthorize faria dentro do controller. */
	@GetMapping("/admin/teste/negado")
	String negado() {
		throw new AccessDeniedException("negado dentro do controller");
	}
}
