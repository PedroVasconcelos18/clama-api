package me.clama.infra.validacao;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CpfOuCnpjValidator implements ConstraintValidator<CpfOuCnpj, String> {

	private static final int[] PESOS_CNPJ = { 6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };

	@Override
	public boolean isValid(String valor, ConstraintValidatorContext ctx) {
		if (valor == null || valor.isBlank()) return true;
		String d = valor.replaceAll("\\D", "");
		return switch (d.length()) {
			case 11 -> d.chars().distinct().count() > 1 && cpf(d) || recusar(ctx, "Confira seu CPF — parece que tem algum dígito errado.");
			case 14 -> d.chars().distinct().count() > 1 && cnpj(d) || recusar(ctx, "Confira seu CNPJ — parece que tem algum dígito errado.");
			default -> recusar(ctx, "CPF deve ter 11 dígitos ou CNPJ deve ter 14 dígitos.");
		};
	}

	private static boolean recusar(ConstraintValidatorContext ctx, String mensagem) {
		ctx.disableDefaultConstraintViolation();
		ctx.buildConstraintViolationWithTemplate(mensagem.replace("{", "\\{")).addConstraintViolation();
		return false;
	}

	private static boolean cpf(String d) {
		for (int n = 9; n <= 10; n++) {
			int soma = 0;
			for (int i = 0; i < n; i++) soma += (d.charAt(i) - '0') * (n + 1 - i);
			int dv = (soma * 10) % 11 % 10;
			if (dv != d.charAt(n) - '0') return false;
		}
		return true;
	}

	private static boolean cnpj(String d) {
		for (int n = 12; n <= 13; n++) {
			int soma = 0;
			for (int i = 0; i < n; i++) soma += (d.charAt(i) - '0') * PESOS_CNPJ[i + 13 - n];
			int r = soma % 11;
			if ((r < 2 ? 0 : 11 - r) != d.charAt(n) - '0') return false;
		}
		return true;
	}
}
