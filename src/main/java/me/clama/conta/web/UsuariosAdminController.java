package me.clama.conta.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import me.clama.conta.interno.AdminDeUsuariosServico;
import me.clama.conta.interno.Papel;
import me.clama.infra.seguranca.RespostasDeSeguranca;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tela de usuarios do admin: ADMIN le (cadeia de seguranca), so SUPERUSUARIO escreve. */
@RestController
@RequestMapping("/api/admin/usuarios")
class UsuariosAdminController {

	static final String PASTORAL_SO_SUPERUSUARIO = "Só o superusuário gerencia usuários.";

	record Criacao(@NotBlank @Email @Size(max = 254) String email, @Size(max = 120) String nomeCompleto,
			@NotBlank @Size(max = 1024) String senha) {}

	record Alteracao(Papel papel, Boolean ativo) {}

	record NovaSenha(@NotBlank @Size(max = 1024) String senha) {}

	private final AdminDeUsuariosServico servico;

	UsuariosAdminController(AdminDeUsuariosServico servico) {
		this.servico = servico;
	}

	/** Ordem fixa (criadoEm decrescente): ordenar por campo do cliente deixaria sondar a senha pela ordem. */
	@GetMapping
	PagedModel<UsuarioAdminItem> listar(@RequestParam(required = false) @Size(max = 100) String q,
			@RequestParam(required = false) Papel papel, @RequestParam(required = false) Boolean ativo,
			@PageableDefault(size = 20) Pageable pagina) {
		var fixa = PageRequest.of(pagina.getPageNumber(), pagina.getPageSize(),
				Sort.by(Sort.Direction.DESC, "criadoEm", "id"));
		return new PagedModel<>(servico.listar(q, papel, ativo, fixa).map(UsuarioAdminItem::de));
	}

	@GetMapping("/{id}")
	UsuarioAdminItem detalhar(@PathVariable Long id) {
		return UsuarioAdminItem.de(servico.detalhar(id));
	}

	@PostMapping
	@PreAuthorize("hasRole('SUPERUSUARIO')")
	@ResponseStatus(HttpStatus.CREATED)
	UsuarioAdminItem criar(@Valid @RequestBody Criacao corpo, Authentication auth) {
		return UsuarioAdminItem.de(servico.criar(autor(auth), corpo.email(), corpo.nomeCompleto(), corpo.senha()));
	}

	@PatchMapping("/{id}")
	@PreAuthorize("hasRole('SUPERUSUARIO')")
	UsuarioAdminItem alterar(@PathVariable Long id, @RequestBody Alteracao corpo, Authentication auth) {
		return UsuarioAdminItem.de(servico.alterar(autor(auth), id, corpo.papel(), corpo.ativo()));
	}

	@PostMapping("/{id}/senha")
	@PreAuthorize("hasRole('SUPERUSUARIO')")
	Map<String, String> definirSenha(@PathVariable Long id, @Valid @RequestBody NovaSenha corpo, Authentication auth) {
		servico.definirSenha(autor(auth), id, corpo.senha());
		return Map.of("mensagemPastoral", "Senha definida. A pessoa precisa entrar de novo.");
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasRole('SUPERUSUARIO')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void excluir(@PathVariable Long id, Authentication auth) {
		servico.excluir(autor(auth), id);
	}

	/** Aqui o 403 so pode ser de papel (a cadeia ja barrou anonimo e cliente): a mensagem diz o motivo. */
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> semPermissao() {
		var p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Acesso negado.");
		p.setProperty("codigo", RespostasDeSeguranca.CODIGO_SEM_PERMISSAO);
		p.setProperty("mensagemPastoral", PASTORAL_SO_SUPERUSUARIO);
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(p);
	}

	private static Long autor(Authentication auth) {
		return Long.valueOf(auth.getName());
	}
}
