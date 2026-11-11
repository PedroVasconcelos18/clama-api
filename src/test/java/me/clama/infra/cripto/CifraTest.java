package me.clama.infra.cripto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CifraTest {

	private static String chave() {
		var b = new byte[32];
		new SecureRandom().nextBytes(b);
		return Base64.getEncoder().encodeToString(b);
	}

	private final Cifra cifra = new Cifra(chave());

	@Test
	void idaEVolta() {
		assertThat(cifra.decifrar(cifra.cifrar("João da Silva ✝"))).isEqualTo("João da Silva ✝");
		assertThat(cifra.decifrar(cifra.cifrar(""))).isEmpty();
	}

	@Test
	void formatoVersionadoENonceDistinto() {
		var a = cifra.cifrar("igual");
		var b = cifra.cifrar("igual");
		assertThat(a).startsWith("v1:").isNotEqualTo(b);
		// nonce(12) + texto(5) + tag(16)
		assertThat(Base64.getDecoder().decode(a.substring(3))).hasSize(12 + 5 + 16);
		assertThat(a).doesNotContain("igual");
	}

	@Test
	void adulteracaoEChaveErradaFalham() {
		var c = cifra.cifrar("segredo");
		var bytes = Base64.getDecoder().decode(c.substring(3));
		bytes[14] ^= 1;
		assertThatThrownBy(() -> cifra.decifrar("v1:" + Base64.getEncoder().encodeToString(bytes)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new Cifra(chave()).decifrar(c)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cifra.decifrar("v2:" + c.substring(3))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cifra.decifrar("texto puro")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void chaveInvalidaRecusada() {
		assertThatThrownBy(() -> new Cifra("")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new Cifra(Base64.getEncoder().encodeToString(new byte[16])))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new Cifra("nao e base64!")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void campoCifradoPreservaNulo() {
		var conv = new CampoCifrado(cifra);
		assertThat(conv.convertToDatabaseColumn(null)).isNull();
		assertThat(conv.convertToEntityAttribute(null)).isNull();
		var col = conv.convertToDatabaseColumn("x");
		assertThat(col).startsWith("v1:");
		assertThat(conv.convertToEntityAttribute(col)).isEqualTo("x");
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ConfigDeCripto.class);

	@Test
	void semChaveOuComChaveCurtaOBootFalha() {
		var ok = chave();
		runner.withPropertyValues("clama.cripto.chave=", "clama.cripto.chave-hmac=" + ok)
				.run(ctx -> assertThat(ctx).hasFailed());
		runner.withPropertyValues("clama.cripto.chave=" + ok, "clama.cripto.chave-hmac=")
				.run(ctx -> assertThat(ctx).hasFailed());
		runner.withPropertyValues("clama.cripto.chave=" + Base64.getEncoder().encodeToString(new byte[16]),
				"clama.cripto.chave-hmac=" + ok).run(ctx -> assertThat(ctx).hasFailed());
		runner.withPropertyValues("clama.cripto.chave=" + ok, "clama.cripto.chave-hmac=" + ok)
				.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(Cifra.class).hasSingleBean(HmacDeIdentificador.class));
	}

	@Test
	void criptoPropsNaoImprimeAsChaves() {
		assertThat(new CriptoProps("chave-secreta-1", "chave-hmac-2").toString()).doesNotContain("chave-secreta-1", "chave-hmac-2");
	}
}
