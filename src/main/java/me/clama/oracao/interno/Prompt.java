package me.clama.oracao.interno;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Um prompt inteiro: o system e as quatro instrucoes (D15). E o corpo do "salvar" no admin e o {@code conteudo} de
 * {@code prompt_versao}. Os textos nao levam PII.
 */
public record Prompt(@NotBlank @Size(max = 50_000) String sistema, @NotBlank @Size(max = 5_000) String simples,
		@NotBlank @Size(max = 5_000) String comVersiculo, @NotBlank @Size(max = 5_000) String comProfeciaEVersiculos,
		@NotBlank @Size(max = 5_000) String gratuito) {
}
