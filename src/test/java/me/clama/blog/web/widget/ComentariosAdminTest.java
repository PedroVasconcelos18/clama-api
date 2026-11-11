package me.clama.blog.web.widget;

import static org.assertj.core.api.Assertions.assertThat;

import me.clama.conta.interno.Papel;
import org.junit.jupiter.api.Test;

/** Moderacao do admin (D11): PagedModel, filtros, sem IP, apagar com auditoria, 401/403. */
class ComentariosAdminTest extends WidgetTeste {

	private long comentario(long postId, long usuarioId, String texto, boolean suspeito, String criadoEm) {
		return jdbc.queryForObject("""
				insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, ip_cifrado, criado_em)
				values (?, ?, ?, ?, 'nao-decifra', ?::timestamptz) returning id""", Long.class, postId, usuarioId, texto, suspeito, criadoEm);
	}

	@Test
	void listaPaginadaMaisNovosPrimeiroSemIp() throws Exception {
		semear(7, "fe", true);
		semear(8, "paz", true);
		var admin = logado("Admin Um", Papel.ADMIN);
		var autora = logado("Ana Maria de Souza", Papel.CLIENTE);
		comentario(7, autora.id(), "primeiro", false, "2026-11-01T10:00:00Z");
		comentario(8, autora.id(), "segundo", true, "2026-11-02T10:00:00Z");
		var res = admin.nav().get("/api/admin/blog/comentarios?size=1");
		assertThat(res.statusCode()).isEqualTo(200); // com o IP ilegivel no banco: a coluna nem e lida
		var j = json(res);
		assertThat(j.get("page").get("totalElements").asInt()).isEqualTo(2);
		assertThat(j.get("content")).hasSize(1);
		var item = j.get("content").get(0);
		assertThat(item.get("conteudo").asString()).isEqualTo("segundo");
		assertThat(item.get("suspeito").asBoolean()).isTrue();
		assertThat(item.get("postSlug").asString()).isEqualTo("paz");
		assertThat(item.get("postTitulo").asString()).isEqualTo("Titulo");
		assertThat(item.get("postUrl").asString()).isEqualTo("https://blog.exemplo/paz/");
		assertThat(item.get("usuarioId").asLong()).isEqualTo(autora.id());
		assertThat(item.get("usuarioEmail").asString()).isEqualTo(autora.email());
		assertThat(item.get("usuarioNome").asString()).isEqualTo("Ana S.");
		assertThat(res.body().toLowerCase()).doesNotContain("ip").doesNotContain("nao-decifra");
		assertThat(json(admin.nav().get("/api/admin/blog/comentarios?size=1&page=1")).get("content").get(0).get("conteudo").asString())
				.isEqualTo("primeiro");
	}

	@Test
	void filtrosSuspeitosPostEUsuario() throws Exception {
		semear(7, "fe", true);
		semear(8, "paz", true);
		var admin = logado("Admin Um", Papel.ADMIN);
		var a = logado();
		var b = logado();
		comentario(7, a.id(), "a-fe", false, "2026-11-01T10:00:00Z");
		comentario(8, a.id(), "a-paz-suspeito", true, "2026-11-02T10:00:00Z");
		comentario(7, b.id(), "b-fe", false, "2026-11-03T10:00:00Z");
		assertThat(json(admin.nav().get("/api/admin/blog/comentarios?suspeitos=true")).get("content")).hasSize(1);
		assertThat(json(admin.nav().get("/api/admin/blog/comentarios?post=fe")).get("page").get("totalElements").asInt()).isEqualTo(2);
		assertThat(json(admin.nav().get("/api/admin/blog/comentarios?usuarioId=" + a.id())).get("page").get("totalElements").asInt()).isEqualTo(2);
		var todos = json(admin.nav().get("/api/admin/blog/comentarios?post=fe&usuarioId=" + a.id() + "&suspeitos=false"));
		assertThat(todos.get("content")).hasSize(1);
		assertThat(todos.get("content").get(0).get("conteudo").asString()).isEqualTo("a-fe");
		assertThat(json(admin.nav().get("/api/admin/blog/comentarios?post=nada")).get("content")).isEmpty();
	}

	@Test
	void apagarDa204ComAuditoriaEInexistenteDa404() throws Exception {
		semear(7, "fe", true);
		var admin = logado("Admin Um", Papel.ADMIN);
		var a = logado();
		var id = comentario(7, a.id(), "ruim", true, "2026-11-01T10:00:00Z");
		assertThat(admin.nav().delete("/api/admin/blog/comentarios/" + id).statusCode()).isEqualTo(204);
		assertThat(quantos("blog_comentario")).isZero();
		var diff = jdbc.queryForObject("select diff::text from auditoria where entidade = 'comentario' and entidade_id = ? and acao = 'EXCLUSAO' and autor_id = ?",
				String.class, String.valueOf(id), admin.id());
		assertThat(diff).contains("\"usuarioId\"").contains("\"postWpId\"").doesNotContain("ruim");
		var res = admin.nav().delete("/api/admin/blog/comentarios/" + id);
		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Esse comentário não foi encontrado.");
	}

	@Test
	void anonimo401ClienteEDa403() throws Exception {
		semear(7, "fe", true);
		var cliente = logado();
		var id = comentario(7, cliente.id(), "texto", false, "2026-11-01T10:00:00Z");
		var anonimo = new me.clama.conta.web.Navegador(porta);
		assertThat(anonimo.get("/api/admin/blog/comentarios").statusCode()).isEqualTo(401);
		assertThat(anonimo.delete("/api/admin/blog/comentarios/" + id).statusCode()).isEqualTo(401);
		assertThat(cliente.nav().get("/api/admin/blog/comentarios").statusCode()).isEqualTo(403);
		assertThat(cliente.nav().delete("/api/admin/blog/comentarios/" + id).statusCode()).isEqualTo(403);
		assertThat(quantos("blog_comentario")).isEqualTo(1);
	}
}
