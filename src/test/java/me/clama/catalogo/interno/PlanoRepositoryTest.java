package me.clama.catalogo.interno;

import static me.clama.catalogo.Complexidade.SIMPLES;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import me.clama.BancoDeTeste;
import me.clama.catalogo.TipoDePlano;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BancoDeTeste.class)
class PlanoRepositoryTest {

	@Autowired PlanoRepository planos;
	@Autowired InstituicaoRepository instituicoes;
	@Autowired InstituicaoLogoRepository logos;
	@Autowired JdbcTemplate jdbc;

	@Test
	void nomeUnicoEntreAtivosMasInativoPodeRepetir() {
		planos.saveAndFlush(Plano.comum("Oracao", "", 2000, SIMPLES, 1));
		assertThrows(DataIntegrityViolationException.class,
				() -> planos.saveAndFlush(Plano.comum("Oracao", "", 3000, SIMPLES, 2)));
	}

	@Test
	void nomeDeInativoLiberaOReuso() {
		var a = planos.saveAndFlush(Plano.comum("Oracao", "", 2000, SIMPLES, 1));
		a.desativar();
		planos.saveAndFlush(a);
		planos.saveAndFlush(Plano.comum("Oracao", "", 3000, SIMPLES, 2));
		assertEquals(2, planos.findAll().stream().filter(p -> p.getNome().equals("Oracao")).count());
	}

	@Test
	void ordemComDesempatePorId() {
		var b = planos.saveAndFlush(Plano.comum("B", "", 2000, SIMPLES, 1));
		var a = planos.saveAndFlush(Plano.comum("A", "", 3000, SIMPLES, 1));
		var zero = planos.saveAndFlush(Plano.comum("Z", "", 4000, SIMPLES, 0));
		var comuns = planos.findByTipoAndAtivoTrueOrderByOrdemAscIdAsc(TipoDePlano.COMUM);
		assertEquals(List.of(zero.getId(), b.getId(), a.getId()), comuns.stream().map(Plano::getId).toList());
	}

	@Test
	void umSoPlanoPorTipoTecnico() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				insert into plano (nome, valor_centavos, complexidade, tipo) values ('Outro', 0, 'SIMPLES', 'GRATUITO')"""));
	}

	@Test
	void tecnicoNaoPodeFicarInativo() {
		assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update("update plano set ativo = false where tipo = 'VALOR_LIVRE'"));
	}

	@Test
	void valorNegativoViolaCheck() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				insert into plano (nome, valor_centavos, complexidade) values ('Neg', -1, 'SIMPLES')"""));
	}

	@Test
	void complexidadeForaDoEnumViolaCheck() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				insert into plano (nome, valor_centavos, complexidade) values ('X', 1, 'simples_gratuita')"""));
	}

	@Test
	void instituicaoOrdemNomeId() {
		var b = instituicoes.saveAndFlush(Instituicao.nova("B", 1));
		var a = instituicoes.saveAndFlush(Instituicao.nova("A", 1));
		var c = instituicoes.saveAndFlush(Instituicao.nova("C", 0));
		assertEquals(List.of(c.getId(), a.getId(), b.getId()),
				instituicoes.findByAtivoTrueOrderByOrdemAscNomeAscIdAsc().stream().map(Instituicao::getId).toList());
		assertThrows(DataIntegrityViolationException.class, () -> instituicoes.saveAndFlush(Instituicao.nova("A", 2)));
	}

	@Test
	void logoGravaEApagaJuntoComAInstituicao() {
		var i = instituicoes.saveAndFlush(Instituicao.nova("Com logo", 1));
		logos.saveAndFlush(new InstituicaoLogo(i.getId(), new byte[] { 1, 2, 3 }, "image/png", "a".repeat(64),
				Instant.parse("2026-11-11T12:00:00Z")));
		assertArrayEquals(new byte[] { 1, 2, 3 }, logos.findById(i.getId()).orElseThrow().getConteudo());
		jdbc.update("delete from instituicao where id = ?", i.getId());
		assertEquals(0, jdbc.queryForObject("select count(*) from instituicao_logo", Integer.class));
	}
}
