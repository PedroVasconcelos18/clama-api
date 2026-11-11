package me.clama.blog.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.clama.blog.interno.Banimentos;
import me.clama.blog.interno.Banimentos.Item;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bans do blog (ADMIN, pela cadeia padrao). Tambem serve a ficha do usuario: {@code ?usuarioId=}. */
@RestController
@RequestMapping("/api/admin/blog/banimentos")
class BanimentosAdminController {

	record Banir(@NotNull Long usuarioId, @NotNull @Size(max = 500) String motivo) {}

	private final Banimentos banimentos;

	BanimentosAdminController(Banimentos banimentos) {
		this.banimentos = banimentos;
	}

	@GetMapping
	PagedModel<Item> listar(@RequestParam(required = false) Long usuarioId,
			@ParameterObject @PageableDefault(size = 20) Pageable pagina) {
		return new PagedModel<>(banimentos.listar(usuarioId, PageRequest.of(pagina.getPageNumber(), pagina.getPageSize())));
	}

	/** 201 com o ban novo; 200 com o existente (o motivo nao muda). */
	@PostMapping
	ResponseEntity<Item> banir(@Valid @RequestBody Banir corpo, Authentication auth) {
		var r = banimentos.banir(Long.parseLong(auth.getName()), corpo.usuarioId(), corpo.motivo());
		return ResponseEntity.status(r.criado() ? HttpStatus.CREATED : HttpStatus.OK).body(r.ban());
	}

	@DeleteMapping("/{usuarioId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revogar(@PathVariable Long usuarioId, Authentication auth) {
		banimentos.revogar(Long.parseLong(auth.getName()), usuarioId);
	}
}
