package me.clama.api.comum.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetentativaTest {
	static final List<Duration> ESPERAS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

	@Test
	void esperaEntreTentativasMasNaoDepoisDaUltima() {
		List<Duration> dormiu = new ArrayList<>();
		AtomicInteger n = new AtomicInteger();
		assertThatThrownBy(() -> Retentativa.executar(() -> {
			n.incrementAndGet();
			throw new HttpStatusException(503);
		}, 3, ESPERAS, Retentativa::padrao, dormiu::add)).isInstanceOf(HttpStatusException.class);
		assertThat(n).hasValue(3);
		assertThat(dormiu).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void padraoRetentaConexaoTimeoutE5xxMasNao4xx() {
		assertThat(Retentativa.padrao(new UncheckedIOException(new IOException("x")))).isTrue();
		assertThat(Retentativa.padrao(new UncheckedIOException(new java.net.http.HttpTimeoutException("t")))).isTrue();
		assertThat(Retentativa.padrao(new HttpStatusException(500))).isTrue();
		assertThat(Retentativa.padrao(new HttpStatusException(422))).isFalse();
		assertThat(Retentativa.padrao(new IllegalStateException())).isFalse();
	}

	@Test
	void retornaOValorQuandoPassa() {
		assertThat(Retentativa.executar(() -> "ok", 3, ESPERAS, Retentativa::padrao, d -> {})).isEqualTo("ok");
	}
}
