package me.clama.api.identidade.senha;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SenhaDjangoTest {
	private final SenhaDjango senha = new SenhaDjango();

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> casos(String secao) throws Exception {
		try (InputStream in = SenhaDjangoTest.class.getResourceAsStream("/contrato/compat-cripto.json")) {
			Map<String, Object> raiz = new ObjectMapper().readValue(in, Map.class);
			return (List<Map<String, Object>>) ((Map<String, Object>) raiz.get(secao)).get("casos");
		}
	}

	@Test
	void confereHashesDoPython() throws Exception {
		for (var c : casos("senhas")) {
			assertEquals(c.get("confere"), senha.matches((String) c.get("senha"), (String) c.get("hash")), c.toString());
		}
	}

	@Test
	void hashVazioOuNuloNaoConfere() {
		assertFalse(senha.matches("x", null));
		assertFalse(senha.matches("x", ""));
		assertFalse(senha.matches("x", "lixo"));
	}

	@Test
	void encodeEhLidoDeVolta() {
		String h = senha.encode("Clama-forte-2026");
		assertTrue(h.startsWith("argon2$argon2id$v=19$m=102400,t=2,p=8$"), h);
		assertTrue(senha.matches("Clama-forte-2026", h));
		assertFalse(senha.matches("outra", h));
	}

	@Test
	void pbkdf2PedeUpgrade() throws Exception {
		String pbkdf2 = (String) casos("senhas").get(1).get("hash");
		assertTrue(senha.upgradeEncoding(pbkdf2));
		assertFalse(senha.upgradeEncoding(senha.encode("x")));
	}
}
