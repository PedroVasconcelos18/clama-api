package me.clama.oracao.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import me.clama.oracao.interno.AdminDeDocumentos;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin dos documentos de contexto (D14; ADMIN pela cadeia padrao). O arquivo vai no corpo cru do PUT (PDF ou TXT, ate
 * 100 MB), nao em multipart: os limites globais de multipart (300 KB) nao se aplicam, e o Tomcat nao limita corpo cru
 * que nao seja formulario. Escritas auditadas.
 */
@RestController
@RequestMapping("/api/admin/documentos")
class DocumentosAdminController {

	record NovoDocumento(@NotBlank @Size(max = 200) String nome) {}

	record MudancaDeAtivo(@NotNull Boolean ativo) {}

	record DocumentoAdmin(long id, String nome, @Nullable String mime, @Nullable Long tamanhoBytes, @Nullable String fileId, boolean ativo,
			Instant criadoEm, Instant atualizadoEm) {

		static DocumentoAdmin de(AdminDeDocumentos.Documento d) {
			return new DocumentoAdmin(d.id(), d.nome(), d.mime(), d.tamanhoBytes(), d.fileId(), d.ativo(), d.criadoEm(), d.atualizadoEm());
		}
	}

	private final AdminDeDocumentos servico;

	DocumentosAdminController(AdminDeDocumentos servico) {
		this.servico = servico;
	}

	@GetMapping
	List<DocumentoAdmin> listar() {
		return servico.listar().stream().map(DocumentoAdmin::de).toList();
	}

	/** So os metadados; o arquivo vem no PUT. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	DocumentoAdmin criar(@Valid @RequestBody NovoDocumento c, Authentication auth) {
		return DocumentoAdmin.de(servico.criar(autor(auth), c.nome()));
	}

	/**
	 * Corpo cru (qualquer Content-Type: o formato sai do conteudo). 400 {@code documento_invalido} ou
	 * {@code documento_grande_demais}; 404 documento; 502 {@code anthropic_falhou}.
	 */
	@PutMapping("/{id}/arquivo")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = {
			@io.swagger.v3.oas.annotations.media.Content(mediaType = "application/pdf",
					schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")),
			@io.swagger.v3.oas.annotations.media.Content(mediaType = "text/plain",
					schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")) })
	DocumentoAdmin enviarArquivo(@PathVariable long id, HttpServletRequest req, Authentication auth) throws IOException {
		return DocumentoAdmin.de(servico.receberArquivo(autor(auth), id, req.getInputStream()));
	}

	/** Ativar confere o arquivo na Files API: 409 {@code documento_sem_arquivo} ou {@code documento_arquivo_sumiu}. */
	@PatchMapping("/{id}")
	DocumentoAdmin alterar(@PathVariable long id, @Valid @RequestBody MudancaDeAtivo c, Authentication auth) {
		return DocumentoAdmin.de(servico.definirAtivo(autor(auth), id, c.ativo()));
	}

	/** Apaga na Anthropic primeiro (404 de la conta como apagado); outra falha de la e 502 e a linha fica. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void apagar(@PathVariable long id, Authentication auth) {
		servico.apagar(autor(auth), id);
	}

	private static Long autor(Authentication auth) {
		return Long.valueOf(auth.getName());
	}
}
