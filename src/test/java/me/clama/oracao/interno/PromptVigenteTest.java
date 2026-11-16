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
		// o "Clama Pastoral v6" de producao; o texto inteiro, caractere a caractere, no ParidadeComDjangoTest
		assertThat(p.sistema()).hasSize(12_120).startsWith("Identidade\nVocê é o Clama").contains("CVV")
				.endsWith("Palavras que cabem no colo, não no púlpito.");
		assertThat(p.simples()).startsWith("Gere uma oração pastoral em exatamente 3 parágrafos curtos.");
		assertThat(p.comVersiculo()).startsWith("Escreva uma oração em 1ª pessoa (a própria pessoa orando a Deus) com 3-4 parágrafos.");
		assertThat(p.comProfeciaEVersiculos()).startsWith("Escreva uma oração em 1ª pessoa (a própria pessoa orando a Deus) com 6-8 parágrafos.");
		assertThat(p.gratuito()).isEqualTo(p.simples()); // em producao simples_gratuita e simples tem o mesmo texto
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
