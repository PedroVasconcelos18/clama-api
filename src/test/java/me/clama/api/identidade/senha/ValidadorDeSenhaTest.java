package me.clama.api.identidade.senha;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValidadorDeSenhaTest {
	private static final String CURTA = "Esta senha é muito curta. Ela precisa conter pelo menos 8 caracteres.";
	private static final String COMUM = "Esta senha é muito comum.";
	private static final String NUMERICA = "Esta senha é inteiramente numérica.";
	private final ValidadorDeSenha v = new ValidadorDeSenha();

	@Test
	void mensagensDoDjango() {
		assertEquals(List.of(CURTA, COMUM, NUMERICA), v.validar("123", "a@b.com"));
		assertEquals(List.of(COMUM, NUMERICA), v.validar("12345678", "a@b.com"));
		assertEquals(List.of(COMUM), v.validar("password", "a@b.com"));
		assertEquals(List.of(), v.validar("Clama-forte-2026", "a@b.com"));
	}

	@Test
	void similaridadeBateComPython() throws Exception {
		for (var c : SenhaDjangoTest.casos("similaridade")) {
			var erros = v.validar((String) c.get("senha"), (String) c.get("email"));
			assertEquals(c.get("rejeita"), erros.contains(ValidadorDeSenha.PARECIDA), c.toString());
		}
		assertEquals("A senha é muito parecida com E-mail", ValidadorDeSenha.PARECIDA);
	}
}
