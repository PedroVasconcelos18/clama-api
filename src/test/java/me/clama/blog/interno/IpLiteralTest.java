package me.clama.blog.interno;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** O IP do comentario so existe para o Marco Civil: o que nao e IP literal vira nulo. */
class IpLiteralTest {

	@Test
	void lixoENuloViramNulo() {
		for (var ruim : new String[] { null, "", "   ", "lixo", "<script>", "999.1.1.1", "a.b.c.d", "exemplo.com", "1.2.3.4, 5.6.7.8" })
			assertThat(Comentarios.ipLiteral(ruim)).as(String.valueOf(ruim)).isNull();
	}

	@Test
	void ipv4Passa() {
		assertThat(Comentarios.ipLiteral("203.0.113.9")).isEqualTo("203.0.113.9");
		assertThat(Comentarios.ipLiteral("  203.0.113.9 ")).isEqualTo("203.0.113.9");
	}

	@Test
	void ipv6PassaNormalizado() {
		assertThat(Comentarios.ipLiteral("::1")).isEqualTo("0:0:0:0:0:0:0:1");
		assertThat(Comentarios.ipLiteral("2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
	}
}
