package me.clama.api.comum.fila;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FilaConfigTest {
	record P() {}

	static TipoDeJob<P> tipo(String nome) {
		return new TipoDeJob<>() {
			public String nome() { return nome; }
			public Class<P> payload() { return P.class; }
			public Duration esperaEntreTentativas() { return Duration.ofSeconds(30); }
			public void executar(P p) {}
		};
	}

	@Test
	@SuppressWarnings("unchecked")
	void nomeDuplicadoNaoSobe() {
		ObjectProvider<TipoDeJob<?>> tipos = mock(ObjectProvider.class);
		when(tipos.orderedStream()).thenReturn(Stream.of(tipo("oracao"), tipo("oracao")));
		assertThatThrownBy(() -> new FilaConfig().tarefasDaFila(tipos, (n, e) -> {}))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("oracao");
	}
}
