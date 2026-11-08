package me.clama.infra.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelosDeEmailTest {

	final ModelosDeEmail modelos = new ModelosDeEmail();

	@Test
	void substituiNoTxtSemEscapeENoHtmlComEscape() {
		var email = modelos.montar("a@exemplo.com", "Assunto", "redefinicao_senha",
				Map.of("primeiro_nome", "<Ana> & \"Cia\"", "link", "http://x/redefinir-senha?token=abc"));
		assertThat(email.texto()).contains("<Ana> & \"Cia\"").contains("http://x/redefinir-senha?token=abc")
				.doesNotContain("{{");
		assertThat(email.html()).contains("&lt;Ana&gt; &amp; &quot;Cia&quot;").doesNotContain("<Ana>")
				.doesNotContain("{{");
		assertThat(email.para()).isEqualTo("a@exemplo.com");
	}

	@Test
	void chaveFaltandoEErro() {
		assertThatThrownBy(() -> modelos.montar("a@b.com", "x", "redefinicao_senha", Map.of("link", "l")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("primeiro_nome");
	}
}
