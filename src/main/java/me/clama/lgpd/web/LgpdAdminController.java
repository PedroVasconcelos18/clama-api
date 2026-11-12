package me.clama.lgpd.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.lgpd.interno.BuscaDoTitular;
import me.clama.lgpd.interno.Exportacao;
import me.clama.lgpd.interno.ExclusaoDoTitular;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LGPD no admin (ADMIN pela cadeia padrao; a exclusao, so SUPERUSUARIO). O e-mail vai sempre no corpo, nunca na URL
 * (INV:247), e nunca para log ou auditoria.
 */
@RestController
@RequestMapping("/api/admin/lgpd")
class LgpdAdminController {

	static final String PASTORAL_SO_SUPERUSUARIO = "Só o superusuário exclui os dados de um titular.";

	record BuscaLgpd(@NotBlank @Size(max = 254) String email) {}

	record ExclusaoLgpd(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 254) String confirmacao,
			@NotNull @Size(max = 10000) List<UUID> pedidoIds, @NotNull @Size(max = 100) List<Long> contaIds) {}

	record ExclusaoDoBlogLgpd(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 254) String confirmacao) {}

	private final BuscaDoTitular busca;
	private final Exportacao exportacao;
	private final ExclusaoDoTitular exclusao;
	private final Clock relogio;

	LgpdAdminController(BuscaDoTitular busca, Exportacao exportacao, ExclusaoDoTitular exclusao, Clock relogio) {
		this.busca = busca;
		this.exportacao = exportacao;
		this.exclusao = exclusao;
		this.relogio = relogio;
	}

	@PostMapping("/busca")
	BuscaDoTitular.PreviaDoTitular buscarTitular(@Valid @RequestBody BuscaLgpd corpo) {
		return busca.previa(corpo.email());
	}

	@PostMapping(path = "/exportacao", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Map<String, Object>> exportarTitular(@Valid @RequestBody BuscaLgpd corpo, Authentication auth) {
		var json = exportacao.exportar(autor(auth), corpo.email());
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
				"attachment; filename=\"" + Exportacao.nomeDoArquivo(relogio.instant()) + "\"").body(json);
	}

	@PostMapping("/exclusao")
	@PreAuthorize("hasRole('SUPERUSUARIO')")
	ExclusaoDoTitular.ResultadoDaExclusao excluirTitular(@Valid @RequestBody ExclusaoLgpd corpo, Authentication auth) {
		return exclusao.excluir(autor(auth), corpo.email(), corpo.confirmacao(), corpo.pedidoIds(), corpo.contaIds());
	}

	@PostMapping("/exclusao-do-blog")
	ExclusaoDoTitular.BlogApagado excluirBlogDoTitular(@Valid @RequestBody ExclusaoDoBlogLgpd corpo, Authentication auth) {
		return exclusao.excluirDoBlog(autor(auth), corpo.email(), corpo.confirmacao());
	}

	/** Aqui o 403 so pode ser de papel (a cadeia ja barrou anonimo e cliente). */
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
