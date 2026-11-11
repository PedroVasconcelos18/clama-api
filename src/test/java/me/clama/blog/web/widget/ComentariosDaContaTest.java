package me.clama.blog.web.widget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** "Seus comentarios" (pergunta 2): so os da propria conta; apagar o de outra pessoa e 404. */
class ComentariosDaContaTest extends WidgetTeste {

	private long comentario(long usuarioId, String texto, String criadoEm) {
		return jdbc.queryForObject("""
				insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, ip_cifrado, criado_em)
				values (7, ?, ?, false, 'nao-decifra', ?::timestamptz) returning id""", Long.class, usuarioId, texto, criadoEm);
	}

	@Test
	void listaSoOsProprios() throws Exception {
		semear(7, "fe", true);
		var eu = logado();
		var outra = logado();
		comentario(eu.id(), "antigo", "2026-11-01T10:00:00Z");
		comentario(eu.id(), "novo", "2026-11-02T10:00:00Z");
		comentario(outra.id(), "alheio", "2026-11-03T10:00:00Z");
		var res = eu.nav().get("/api/conta/comentarios");
		assertThat(res.statusCode()).isEqualTo(200);
		var j = json(res);
		assertThat(j.get("page").get("totalElements").asInt()).isEqualTo(2);
		assertThat(j.get("content").get(0).get("conteudo").asString()).isEqualTo("novo");
		assertThat(j.get("content").get(0).get("postSlug").asString()).isEqualTo("fe");
		assertThat(j.get("content").get(0).get("postTitulo").asString()).isEqualTo("Titulo");
		assertThat(j.get("content").get(0).get("postUrl").asString()).isEqualTo("https://blog.exemplo/fe/");
		assertThat(res.body()).doesNotContain("alheio").doesNotContain("nao-decifra").doesNotContain("usuario");
		assertThat(json(eu.nav().get("/api/conta/comentarios?size=1&page=1")).get("content").get(0).get("conteudo").asString()).isEqualTo("antigo");
	}

	@Test
	void apagarOProprioDa204EODeOutroDa404() throws Exception {
		semear(7, "fe", true);
		var eu = logado();
		var outra = logado();
		var meu = comentario(eu.id(), "meu", "2026-11-01T10:00:00Z");
		var dela = comentario(outra.id(), "dela", "2026-11-01T11:00:00Z");
		var alheio = eu.nav().delete("/api/conta/comentarios/" + dela);
		assertThat(alheio.statusCode()).isEqualTo(404);
		assertThat(json(alheio).get("mensagemPastoral").asString()).isEqualTo("Esse comentário não foi encontrado.");
		assertThat(quantos("blog_comentario")).isEqualTo(2);
		assertThat(eu.nav().delete("/api/conta/comentarios/" + meu).statusCode()).isEqualTo(204);
		assertThat(eu.nav().delete("/api/conta/comentarios/" + meu).statusCode()).isEqualTo(404);
		assertThat(quantos("blog_comentario")).isEqualTo(1);
	}

	@Test
	void anonimoDa401() throws Exception {
		var anonimo = new me.clama.conta.web.Navegador(porta);
		assertThat(anonimo.get("/api/conta/comentarios").statusCode()).isEqualTo(401);
		assertThat(anonimo.delete("/api/conta/comentarios/1").statusCode()).isEqualTo(401);
	}
}
