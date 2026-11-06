package me.clama.api.comum.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaixaDeSaidaEmMemoriaTest {
	static final Email EMAIL = new Email("ana@exemplo.com", "Assunto", "texto", "<p>html</p>");

	@Test
	void chaveRepetidaSimulaAResendEViraJaEnviado() {
		var caixa = new CaixaDeSaidaEmMemoria();
		assertThat(caixa.enviar(EMAIL.comChave("oracao/1"))).isEqualTo(ResultadoEnvio.ENVIADO);
		assertThat(caixa.enviar(EMAIL.comChave("oracao/1"))).isEqualTo(ResultadoEnvio.JA_ENVIADO);
		assertThat(caixa.enviados()).hasSize(1);
		caixa.limpar();
		assertThat(caixa.enviar(EMAIL.comChave("oracao/1"))).isEqualTo(ResultadoEnvio.ENVIADO);
	}

	@Test
	void semChaveSempreEnvia() {
		var caixa = new CaixaDeSaidaEmMemoria();
		caixa.enviar(EMAIL);
		assertThat(caixa.enviar(EMAIL)).isEqualTo(ResultadoEnvio.ENVIADO);
		assertThat(caixa.enviados()).hasSize(2);
	}
}
