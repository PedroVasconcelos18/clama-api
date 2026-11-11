package me.clama.oracao.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.clama.TesteDeRepositorio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** D15: sem versao ativa vale o padrao do codigo; a ativa vence; o banco recusa duas ativas. */
@TesteDeRepositorio
@Import(PromptVigente.class)
class PromptVigenteTest {

	@Autowired PromptVigente vigente;
	@Autowired JdbcTemplate jdbc;

	@Test
	void semLinhaValeOPadraoDoCodigo() {
		var v = vigente.vigente();
		assertThat(v.versaoId()).isNull();
		assertThat(v.prompt()).isEqualTo(vigente.padrao());
		var p = v.prompt();
		assertThat(p.sistema()).startsWith("# Identidade\n\nVocê é o **Clama**").contains("CVV")
				.endsWith("com palavras que cabem no colo, não no púlpito.");
		assertThat(p.simples()).startsWith("Complexidade: SIMPLES\n");
		assertThat(p.comVersiculo()).startsWith("Complexidade: COM_VERSICULO\n");
		assertThat(p.comProfeciaEVersiculos()).startsWith("Complexidade: COM_PROFECIA_E_VERSICULOS\n");
		assertThat(p.gratuito()).startsWith("Gere uma oração pastoral em exatamente 3 parágrafos curtos.")
				.endsWith("sem digressões.");
	}

	@Test
	void versaoInativaNaoVale() {
		inserir("Inativo.", false);
		assertThat(vigente.vigente().versaoId()).isNull();
	}

	@Test
	void ativaVence() {
		inserir("Outro.", false);
		var id = inserir("Ativo.", true);
		var v = vigente.vigente();
		assertThat(v.versaoId()).isEqualTo(id);
		assertThat(v.prompt().sistema()).isEqualTo("Ativo.");
		assertThat(v.prompt().gratuito()).isEqualTo("g");
	}

	@Test
	void indiceImpedeDuasAtivas() {
		inserir("Um.", true);
		assertThatThrownBy(() -> inserir("Dois.", true)).isInstanceOf(DataIntegrityViolationException.class);
	}

	/** Versao e imutavel: o banco recusa UPDATE do conteudo; o resto (ativo) muda. */
	@Test
	void conteudoNaoMuda() {
		var id = inserir("Original.", false);
		assertThatThrownBy(() -> jdbc.update("update prompt_versao set conteudo = '{}'::jsonb where id = ?", id))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("imutavel");
	}

	@Test
	void ativoMuda() {
		var id = inserir("Original.", false);
		assertThat(jdbc.update("update prompt_versao set ativo = true, conteudo = conteudo where id = ?", id)).isEqualTo(1);
	}

	long inserir(String sistema, boolean ativo) {
		var conteudo = PromptVigente.json(new Prompt(sistema, "s", "v", "p", "g"));
		return jdbc.queryForObject("insert into prompt_versao (conteudo, criado_em, ativo) values (?::jsonb, now(), ?) returning id",
				Long.class, conteudo, ativo);
	}
}
