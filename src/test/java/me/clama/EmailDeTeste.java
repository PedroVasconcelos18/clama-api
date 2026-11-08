package me.clama;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import me.clama.infra.email.EnviadorDeEmail;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Caixa de saida em memoria no lugar da Resend e relogio que anda para frente: {@code @Import(EmailDeTeste.class)}. */
@TestConfiguration(proxyBeanMethods = false)
public class EmailDeTeste {

	/** Relogio dos testes: comeca agora e so avanca quando o teste manda. */
	public static class RelogioAvancavel extends Clock {
		private volatile Duration avanco = Duration.ZERO;

		public void avancar(Duration d) { avanco = avanco.plus(d); }

		public void zerar() { avanco = Duration.ZERO; }

		@Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(java.time.ZoneId zone) { return this; }
		@Override public Instant instant() { return Instant.now().plus(avanco); }
	}

	@Bean
	@Primary
	CaixaDeSaidaEmMemoria caixaDeSaida() {
		return new CaixaDeSaidaEmMemoria();
	}

	@Bean
	@Primary
	RelogioAvancavel relogioDeTeste() {
		return new RelogioAvancavel();
	}
}
