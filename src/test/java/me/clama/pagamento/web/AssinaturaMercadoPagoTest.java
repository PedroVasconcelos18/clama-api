package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Porte do {@code AssinaturaMercadoPagoTest} do sistema antigo, com os mesmos vetores (gerados no Python do clama-backend):
 *
 * <pre>
 * def s(sec,did,rid,ts): return hmac.new(sec.encode(), f'id:{did.lower()};request-id:{rid};ts:{ts};'.encode(),
 *     hashlib.sha256).hexdigest()
 * s('segredo-webhook-contrato','1325814423','req-contrato-1','1762434000')  # 22493a9b...
 * s('segredo-webhook-contrato','ABC123def','req-contrato-1','1762434000')   # 37d1947b...
 * s('segredo-webhook-contrato','1325814423','','1762434000')                # 25c3ae0d...
 * </pre>
 */
class AssinaturaMercadoPagoTest {
	static final String SEGREDO = "segredo-webhook-contrato";
	static final String OK = "22493a9bb460d826f1fa62511c08c35fa6b1aa5699085cc1015e2c6afe23aceb";
	static final String MAIUSCULAS = "37d1947b84124d13a7c35a5e5925b69cb68a03c0f24fdc8433924737ff0992ad";
	static final String SEM_REQUEST_ID = "25c3ae0db2d721b1ab10603f97364519f2b0c0ea0ab329f28429c6bf72a822b2";

	static boolean valida(String query, String requestId, String assinatura) {
		return AssinaturaMercadoPago.valida(SEGREDO, query, requestId, assinatura);
	}

	@Test
	void vetorGravado() {
		assertThat(valida("data.id=1325814423&type=payment", "req-contrato-1", "ts=1762434000,v1=" + OK)).isTrue();
	}

	@Test
	void dataIdEntraEmMinusculas() {
		assertThat(valida("data.id=ABC123def", "req-contrato-1", "ts=1762434000,v1=" + MAIUSCULAS)).isTrue();
	}

	@Test
	void semRequestIdEntraVazio() {
		assertThat(valida("data.id=1325814423", null, "ts=1762434000,v1=" + SEM_REQUEST_ID)).isTrue();
		assertThat(valida("data.id=1325814423", null, "ts=1762434000,v1=" + OK)).isFalse();
	}

	@Test
	void partesComEspacosEUltimaChaveVence() {
		assertThat(valida("data.id=1325814423", "req-contrato-1", " ts=1762434000 , v1=" + OK + " ")).isTrue();
		assertThat(valida("data.id=1325814423", "req-contrato-1", "ts=1,ts=1762434000,v1=" + OK)).isTrue();
	}

	@Test
	void queryComoODjango() {
		// QueryDict.get: o ultimo valor; chave e valor decodificados
		assertThat(valida("data.id=999&data.id=1325814423", "req-contrato-1", "ts=1762434000,v1=" + OK)).isTrue();
		assertThat(valida("data%2Eid=13258%314423", "req-contrato-1", "ts=1762434000,v1=" + OK)).isTrue();
	}

	@Test
	void recusa() {
		assertThat(valida("data.id=1325814423", "req-contrato-1", "ts=1762434000,v1=" + OK.toUpperCase()))
				.as("v1 comparado exato").isFalse();
		assertThat(valida("data.id=1325814423", "req-contrato-1", "ts=1762434000,v1=é" + OK.substring(1)))
				.as("v1 nao-ASCII").isFalse();
		assertThat(valida("data.id=1325814423", "req-contrato-2", "ts=1762434000,v1=" + OK)).isFalse();
		assertThat(valida("data.id=1325814423", "req-contrato-1", "ts=1762434001,v1=" + OK)).isFalse();
		assertThat(valida("data.id=1325814423", "req-contrato-1", "ts=1762434000")).as("sem v1").isFalse();
		assertThat(valida("data.id=1325814423", "req-contrato-1", "v1=" + OK)).as("sem ts").isFalse();
		assertThat(valida("data.id=1325814423", "req-contrato-1", null)).as("sem header").isFalse();
		assertThat(valida("type=payment", "req-contrato-1", "ts=1762434000,v1=" + OK)).as("sem data.id").isFalse();
		assertThat(valida(null, "req-contrato-1", "ts=1762434000,v1=" + OK)).as("sem query").isFalse();
		assertThat(AssinaturaMercadoPago.valida("", "data.id=1325814423", "req-contrato-1", "ts=1762434000,v1=" + OK))
				.as("segredo vazio").isFalse();
	}

	@Test
	void parametroComoODjango() {
		assertThat(AssinaturaMercadoPago.parametro("a=1&data.id=7&data.id=8", "data.id")).isEqualTo("8");
		assertThat(AssinaturaMercadoPago.parametro("data.id=a+b%zz", "data.id")).isEqualTo("a b%zz");
		assertThat(AssinaturaMercadoPago.parametro(null, "data.id")).isEmpty();
	}

	@Test
	void segredoEmBrancoSoSobeEmLocalETeste() {
		assertThatThrownBy(() -> AssinaturaMercadoPago.exigirSegredo(" ", new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("MERCADOPAGO_WEBHOOK_SECRET");
		for (var perfil : new String[] { "local", "test" }) {
			var env = new MockEnvironment();
			env.setActiveProfiles(perfil);
			assertThatCode(() -> AssinaturaMercadoPago.exigirSegredo("", env)).doesNotThrowAnyException();
		}
		assertThat(AssinaturaMercadoPago.exigirSegredo("s", new MockEnvironment())).isEqualTo("s");
	}
}
