package me.clama.conta.interno;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import me.clama.TesteDeRepositorio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@TesteDeRepositorio
class UsuarioRepositoryTest {

	@Autowired UsuarioRepository repo;
	@Autowired JdbcTemplate jdbc;

	private static Usuario novo(String email) {
		return Usuario.novo(email, "Fulana", Papel.CLIENTE, Instant.parse("2026-11-08T12:00:00Z"));
	}

	@Test
	void gravaEmailEmMinusculasECanonico() {
		var u = repo.saveAndFlush(novo("Fu.Lano+x@GoogleMail.com"));
		assertEquals("fu.lano+x@googlemail.com", u.getEmail());
		assertEquals("fulano@gmail.com", repo.findByEmailCanonico("fulano@gmail.com").orElseThrow().getEmailCanonico());
		assertTrue(repo.findByEmail("fu.lano+x@googlemail.com").isPresent());
		assertFalse(u.temSenha());
		assertEquals(1, repo.countByPapelAndAtivoTrue(Papel.CLIENTE));
	}

	@Test
	void emailDuplicadoViolaUnique() {
		repo.saveAndFlush(novo("a@exemplo.com"));
		assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(novo("a@exemplo.com")));
	}

	@Test
	void emailCanonicoDuplicadoViolaUnique() {
		repo.saveAndFlush(novo("fulano@gmail.com"));
		assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(novo("fu.lano+x@gmail.com")));
	}

	@Test
	void emailComMaiusculaViolaCheck() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"insert into usuario (email, email_canonico, papel, criado_em) values ('A@x.com', 'a@x.com', 'CLIENTE', now())"));
	}
}
