package me.clama.conta.senha;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValidadorDeSenhaTest {
	private static final String PARECIDA = "A senha é muito parecida com E-mail";
	private static final String CURTA = "Esta senha é muito curta. Ela precisa conter pelo menos 8 caracteres.";
	private static final String COMUM = "Esta senha é muito comum.";
	private static final String NUMERICA = "Esta senha é inteiramente numérica.";
	private final ValidadorDeSenha v = new ValidadorDeSenha();

	@Test
	void mensagensDoDjangoNaOrdem() {
		assertEquals(List.of(CURTA, COMUM, NUMERICA), v.validar("123", "a@b.com"));
		assertEquals(List.of(COMUM, NUMERICA), v.validar("12345678", "a@b.com"));
		assertEquals(List.of(COMUM), v.validar("password", "a@b.com"));
		assertEquals(List.of(), v.validar("Clama-forte-2026", "a@b.com"));
		assertEquals(List.of(NUMERICA), v.validar("²²²²²²²²", "a@b.com"));
		assertEquals(List.of(NUMERICA), v.validar("①②③④⑤⑥⑦⑧", "a@b.com"));
		assertEquals(List.of(COMUM), v.validar("password ", "a@b.com"));
		assertEquals(List.of(COMUM), v.validar("password\u0085", "a@b.com"));
	}

	@Test
	void similaridadeComEmail() {
		assertEquals(List.of(PARECIDA), v.validar("joaosilva", "joao.silva@exemplo.com"));
		assertEquals(List.of(PARECIDA), v.validar("exemplo123", "joao.silva@exemplo.com"));
		assertEquals(List.of(), v.validar("Clama-forte-2026", "joao.silva@exemplo.com"));
		assertEquals(List.of(), v.validar("joaosilva", null));
	}
}
