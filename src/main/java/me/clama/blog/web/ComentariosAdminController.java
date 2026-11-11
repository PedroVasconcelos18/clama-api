package me.clama.blog.web;

import jakarta.validation.constraints.Size;
import me.clama.blog.interno.GestaoDeComentarios;
import me.clama.blog.interno.GestaoDeComentarios.ParaAdmin;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Moderacao de comentarios (ADMIN, pela cadeia padrao). Sem IP, nunca (D11). */
@RestController
@Validated
@RequestMapping("/api/admin/blog/comentarios")
class ComentariosAdminController {

	private final GestaoDeComentarios comentarios;

	ComentariosAdminController(GestaoDeComentarios comentarios) {
		this.comentarios = comentarios;
	}

	/** Ordem fixa: mais novos primeiro. */
	@GetMapping
	PagedModel<ParaAdmin> listar(@RequestParam(defaultValue = "false") boolean suspeitos,
			@RequestParam(required = false) @Size(max = 200) String post, @RequestParam(required = false) Long usuarioId,
			@ParameterObject @PageableDefault(size = 20) Pageable pagina) {
		var fixa = PageRequest.of(pagina.getPageNumber(), pagina.getPageSize());
		return new PagedModel<>(comentarios.listarParaAdmin(suspeitos, post, usuarioId, fixa));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void apagar(@PathVariable Long id, Authentication auth) {
		comentarios.apagarComoAdmin(Long.parseLong(auth.getName()), id);
	}
}
