package me.clama.catalogo.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import me.clama.catalogo.Complexidade;
import me.clama.catalogo.interno.CatalogoAdminServico;
import me.clama.catalogo.interno.Instituicao;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Admin de planos e instituicoes. ADMIN escreve (cadeia padrao); sem DELETE: desativar e {@code PATCH ativo=false}. */
@RestController
@RequestMapping("/api/admin")
class CatalogoAdminController {

	private static final String SO_BRANCOS = ".*\\S.*";

	record NovoPlano(@NotBlank @Size(max = 80) String nome, @Size(max = 2000) String descricao,
			@NotNull @Min(1) @Max(Integer.MAX_VALUE) Long valorCentavos, Complexidade complexidade,
			@Min(0) @Max(32767) Integer ordem) {}

	/** Nulo = deixa como esta. */
	record MudancaDePlano(@Size(max = 80) @Pattern(regexp = SO_BRANCOS) String nome, @Size(max = 2000) String descricao,
			@Min(0) @Max(Integer.MAX_VALUE) Long valorCentavos, Complexidade complexidade,
			@Min(0) @Max(32767) Integer ordem, Boolean ativo) {}

	record NovaInstituicao(@NotBlank @Size(max = 120) String nome, @Min(0) @Max(32767) Integer ordem,
			@Size(max = 140) String causa, @Min(1) Long metaMensalCentavos, @Min(1) Long custoUnidadeCentavos,
			@Size(max = 40) String unidade) {}

	/**
	 * Nome, ordem e ativo: nulo = deixa como esta. Os 4 campos de meta: ausente = deixa como esta, {@code null} explicito
	 * = limpa, valor = define. Classe com setters (o Jackson so os chama quando o campo veio no JSON, nulo incluido);
	 * a referencia nula do Optional e "ausente", Optional vazio e "limpar". {@code Optional} em record nao serve: o
	 * Jackson 3 entrega Optional vazio tambem para o campo ausente.
	 */
	static class MudancaDeInstituicao {
		@Size(max = 120) @Pattern(regexp = SO_BRANCOS) private String nome;
		@Min(0) @Max(32767) private Integer ordem;
		private Boolean ativo;
		private Optional<@Size(max = 140) String> causa;
		private Optional<@Min(1) Long> metaMensalCentavos;
		private Optional<@Min(1) Long> custoUnidadeCentavos;
		private Optional<@Size(max = 40) String> unidade;

		public void setNome(String v) { nome = v; }
		public void setOrdem(Integer v) { ordem = v; }
		public void setAtivo(Boolean v) { ativo = v; }
		public void setCausa(String v) { causa = Optional.ofNullable(v); }
		public void setMetaMensalCentavos(Long v) { metaMensalCentavos = Optional.ofNullable(v); }
		public void setCustoUnidadeCentavos(Long v) { custoUnidadeCentavos = Optional.ofNullable(v); }
		public void setUnidade(String v) { unidade = Optional.ofNullable(v); }

		String nome() { return nome; }
		Integer ordem() { return ordem; }
		Boolean ativo() { return ativo; }
		Optional<String> causa() { return causa; }
		Optional<Long> metaMensalCentavos() { return metaMensalCentavos; }
		Optional<Long> custoUnidadeCentavos() { return custoUnidadeCentavos; }
		Optional<String> unidade() { return unidade; }
	}

	private final CatalogoAdminServico servico;

	CatalogoAdminController(CatalogoAdminServico servico) {
		this.servico = servico;
	}

	@GetMapping("/planos")
	List<PlanoAdmin> planos() {
		return servico.listarPlanos().stream().map(PlanoAdmin::de).toList();
	}

	@PostMapping("/planos")
	@ResponseStatus(HttpStatus.CREATED)
	PlanoAdmin criarPlano(@Valid @RequestBody NovoPlano c, Authentication auth) {
		return PlanoAdmin.de(servico.criarPlano(autor(auth), c.nome(), c.descricao(), c.valorCentavos(),
				c.complexidade() != null ? c.complexidade() : Complexidade.SIMPLES, c.ordem() != null ? c.ordem() : 0));
	}

	@PatchMapping("/planos/{id}")
	PlanoAdmin alterarPlano(@PathVariable Long id, @Valid @RequestBody MudancaDePlano c, Authentication auth) {
		return PlanoAdmin.de(servico.alterarPlano(autor(auth), id, c.nome(), c.descricao(), c.valorCentavos(),
				c.complexidade(), c.ordem(), c.ativo()));
	}

	@GetMapping("/instituicoes")
	List<InstituicaoAdmin> instituicoes() {
		var todas = servico.listarInstituicoes();
		var hashes = servico.hashesDeLogo(todas.stream().map(Instituicao::getId).toList());
		return todas.stream().map(i -> InstituicaoAdmin.de(i, hashes.get(i.getId()))).toList();
	}

	@PostMapping("/instituicoes")
	@ResponseStatus(HttpStatus.CREATED)
	InstituicaoAdmin criarInstituicao(@Valid @RequestBody NovaInstituicao c, Authentication auth) {
		return InstituicaoAdmin.de(
				servico.criarInstituicao(autor(auth), c.nome(), c.ordem() != null ? c.ordem() : 0, c.causa(),
						c.metaMensalCentavos(), c.custoUnidadeCentavos(), c.unidade()), null);
	}

	@PatchMapping("/instituicoes/{id}")
	InstituicaoAdmin alterarInstituicao(@PathVariable Long id, @Valid @RequestBody MudancaDeInstituicao c,
			Authentication auth) {
		var i = servico.alterarInstituicao(autor(auth), id, c.nome(), c.ordem(), c.ativo(), c.causa(),
				c.metaMensalCentavos(), c.custoUnidadeCentavos(), c.unidade());
		return InstituicaoAdmin.de(i, servico.hashesDeLogo(List.of(id)).get(id));
	}

	/** Multipart com a parte {@code logo}; a validacao e pelos bytes (ValidadorDeLogo), nao pelo Content-Type. */
	@PutMapping("/instituicoes/{id}/logo")
	InstituicaoAdmin trocarLogo(@PathVariable Long id, @RequestParam(name = "logo", required = false) MultipartFile logo,
			Authentication auth) throws IOException {
		var i = servico.trocarLogo(autor(auth), id, logo == null ? null : logo.getBytes());
		return InstituicaoAdmin.de(i, servico.hashesDeLogo(List.of(id)).get(id));
	}

	@DeleteMapping("/instituicoes/{id}/logo")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removerLogo(@PathVariable Long id, Authentication auth) {
		servico.removerLogo(autor(auth), id);
	}

	private static Long autor(Authentication auth) {
		return Long.valueOf(auth.getName());
	}
}
