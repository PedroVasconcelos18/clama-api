package me.clama.pedido;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import me.clama.infra.validacao.CpfOuCnpj;

/**
 * Corpo de {@code POST /api/pedidos}, {@code /api/pedidos/gratuito}, {@code /api/doacoes} e o submit do freemium: cada rota le so o que lhe
 * cabe e ignora o resto. {@code plano}, {@code instituicao} e {@code valorCentavos} so valem no pedido pago;
 * {@code plano} nao vale na doacao (sempre inferido); {@code turnstileToken} so na doacao; o pedido gratuito usa so os
 * dados pessoais. Um {@code deviceHash} que o front antigo ainda mande e ignorado (o Jackson descarta propriedade
 * desconhecida).
 */
public record NovoPedido(
		@NotBlank(message = "Por favor, digite seu nome.")
		@Size.List({ @Size(min = 2, message = "Conta um pouco de você no nome — pelo menos 2 letras."),
				@Size(max = 120, message = "O nome pode ter no máximo 120 caracteres.") }) String nome,
		@NotBlank(message = "Por favor, digite seu e-mail.")
		@Email(message = "Confira seu e-mail — parece que faltou algo.")
		@Size(max = 254, message = "O e-mail pode ter no máximo 254 caracteres.") String email,
		@NotBlank(message = "Por favor, informe seu CPF ou CNPJ.")
		@Size(max = 18, message = "Confira seu CPF ou CNPJ.") @CpfOuCnpj String cpfCnpj,
		@Size(max = 20, message = "O telefone pode ter no máximo 20 caracteres.") String telefone,
		@Min(value = 1, message = "A idade deve estar entre 1 e 120.") @Max(value = 120, message = "A idade deve estar entre 1 e 120.") Short idade,
		@Size(max = 20, message = "Escolha uma opção válida.")
		@Pattern(regexp = "feminino|masculino|nao_informado|", message = "Escolha uma opção válida.") String sexo,
		@Size(max = 2000, message = "O pedido de oração pode ter no máximo 2000 caracteres.") String pedidoOracao,
		Long plano,
		Long instituicao,
		@Min(value = 0, message = "Informe um valor válido.") @Max(value = Integer.MAX_VALUE, message = "Informe um valor válido.") Long valorCentavos,
		@NotNull(message = "Para enviar seu pedido, é preciso concordar com a política de privacidade.")
		@AssertTrue(message = "Para enviar seu pedido, é preciso concordar com a política de privacidade.") Boolean consentAceito,
		@Size(max = 2048, message = "Verificação anti-robô falhou. Atualize a página e tente de novo.") String turnstileToken) {

	/** Tira as pontas dos textos: as regras de tamanho valem para o que a pessoa de fato escreveu. */
	public NovoPedido {
		nome = nome == null ? null : nome.strip();
		email = email == null ? null : email.strip();
		cpfCnpj = cpfCnpj == null ? null : cpfCnpj.strip();
		telefone = telefone == null ? null : telefone.strip();
	}
}
