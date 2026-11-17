package me.clama.oracao.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.oracao.interno.AdminDePrompts;
import me.clama.oracao.interno.ClienteClaude;
import me.clama.oracao.interno.MontagemDoPrompt;
import me.clama.oracao.interno.Prompt;
import me.clama.oracao.interno.PromptVigente;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin de prompts (D15; ADMIN pela cadeia padrao). Versoes imutaveis: salvar cria uma nova, inativa. No maximo uma
 * ativa; sem nenhuma vale o padrao do codigo, que aparece na lista como item {@code padrao} (so leitura). Escritas
 * auditadas.
 */
@RestController
@RequestMapping("/api/admin/prompts")
class PromptsAdminController {

	/** {@code id} nulo so no item padrao. {@code ativo}: e o que a geracao usa agora. */
	record VersaoDePrompt(@Nullable Long id, boolean padrao, boolean ativo, Prompt conteudo, @Nullable Instant criadoEm,
			@Nullable Long criadoPor) {

		static VersaoDePrompt de(AdminDePrompts.Versao v) {
			return new VersaoDePrompt(v.id(), false, v.ativo(), v.conteudo(), v.criadoEm(), v.criadoPor());
		}
	}

	/** Pedido de exemplo da previa; ausentes caem no padrao (Maria, feminino, "Oração de teste"). {@code versaoId} nulo = o padrao do codigo. */
	record PedidoDeExemplo(@Nullable Long versaoId, @Nullable @Size(max = 120) String nome,
			@Nullable @Pattern(regexp = "feminino|masculino|nao_informado|") String sexo, @Nullable @Min(1) @Max(120) Short idade,
			@Nullable @Size(max = 2000) String pedidoOracao) {}

	record Previa(String oracao, @Nullable Long versaoId) {}

	private final AdminDePrompts servico;
	private final PromptVigente vigente;
	private final ClienteClaude claude;

	PromptsAdminController(AdminDePrompts servico, PromptVigente vigente, ClienteClaude claude) {
		this.servico = servico;
		this.vigente = vigente;
		this.claude = claude;
	}

	/** O padrao primeiro, depois as versoes da mais nova para a mais antiga. */
	@GetMapping
	List<VersaoDePrompt> listar() {
		var versoes = servico.listar();
		var lista = new ArrayList<VersaoDePrompt>();
		lista.add(new VersaoDePrompt(null, true, versoes.stream().noneMatch(AdminDePrompts.Versao::ativo), vigente.padrao(), null, null));
		versoes.forEach(v -> lista.add(VersaoDePrompt.de(v)));
		return lista;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	VersaoDePrompt criar(@Valid @RequestBody Prompt prompt, Authentication auth) {
		return VersaoDePrompt.de(servico.criar(autor(auth), prompt));
	}

	@PostMapping("/{id}/ativacao")
	VersaoDePrompt ativar(@PathVariable long id, Authentication auth) {
		return VersaoDePrompt.de(servico.ativar(autor(auth), id));
	}

	/** Volta ao padrao do codigo (nenhuma versao ativa). */
	@DeleteMapping("/ativacao")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void voltarAoPadrao(Authentication auth) {
		servico.voltarAoPadrao(autor(auth));
	}

	/**
	 * Uma oracao de teste com a versao escolhida, sem documentos e sem retentativa. 503 {@code anthropic_sem_creditos} ou
	 * {@code anthropic_indisponivel}; 502 {@code anthropic_resposta_invalida}. A mensagem crua da Anthropic nunca sai.
	 */
	@PostMapping("/previa")
	Previa previa(@Valid @RequestBody PedidoDeExemplo e) {
		var prompt = e.versaoId() == null ? vigente.padrao() : servico.versao(e.versaoId()).conteudo();
		var mensagem = MontagemDoPrompt.mensagem(prompt, valor(e.nome(), "Maria"), valor(e.sexo(), "feminino"), e.idade(), null, valor(e.pedidoOracao(), "Oração de teste"));
		try {
			return new Previa(claude.previa(prompt.sistema(), mensagem), e.versaoId());
		} catch (ClienteClaude.CreditosEsgotados x) {
			throw new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "anthropic_sem_creditos", "Anthropic sem créditos.",
					"A conta da Anthropic está sem créditos. Recarregue para gerar orações.");
		} catch (ClienteClaude.Indisponivel x) {
			throw new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "anthropic_indisponivel", "Claude indisponível.",
					"A Claude não respondeu agora. Tente de novo em instantes.");
		} catch (ClienteClaude.Falha x) {
			throw new ErroDeNegocio(HttpStatus.BAD_GATEWAY, "anthropic_resposta_invalida", "Resposta inválida da Claude.",
					"A Claude devolveu uma resposta que não deu para usar. Tente de novo.");
		}
	}

	private static <T> T valor(@Nullable T v, T padrao) {
		return v == null || v instanceof String s && s.isBlank() ? padrao : v;
	}

	private static Long autor(Authentication auth) {
		return Long.valueOf(auth.getName());
	}
}
