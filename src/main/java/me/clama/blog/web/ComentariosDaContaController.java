package me.clama.blog.web;

import me.clama.blog.interno.GestaoDeComentarios;
import me.clama.blog.interno.GestaoDeComentarios.DaConta;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Seus comentarios no blog": so os da conta logada (cadeia da conta: sessao e CSRF de SPA). */
@RestController
@RequestMapping("/api/conta/comentarios")
class ComentariosDaContaController {

	private final GestaoDeComentarios comentarios;

	ComentariosDaContaController(GestaoDeComentarios comentarios) {
		this.comentarios = comentarios;
	}

	@GetMapping
	PagedModel<DaConta> listar(@ParameterObject @PageableDefault(size = 20) Pageable pagina, Authentication auth) {
		return new PagedModel<>(comentarios.listarDaConta(Long.parseLong(auth.getName()),
				PageRequest.of(pagina.getPageNumber(), pagina.getPageSize())));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void apagar(@PathVariable Long id, Authentication auth) {
		comentarios.apagarDaConta(Long.parseLong(auth.getName()), id);
	}
}
