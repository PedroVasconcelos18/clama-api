package me.clama.blog.web.widget;

import static org.assertj.core.api.Assertions.assertThat;

import me.clama.conta.interno.Papel;
import org.junit.jupiter.api.Test;

/** D10: a regra e das FKs da V7; o conta nao conhece o blog. */
class ExclusaoDeUsuarioComBlogTest extends WidgetTeste {

	@Test
	void apagarQuemComentouCurtiuEFoiBanidoLimpaTudo() throws Exception {
		semear(7, "fe", true);
		var super_ = logado("Super", Papel.SUPERUSUARIO);
		var admin = logado("Admin", Papel.ADMIN);
		var alvo = logado();
		var outra = logado();
		assertThat(comentarTexto(alvo.nav(), "fe", "meu comentario").statusCode()).isEqualTo(201);
		assertThat(comentarTexto(outra.nav(), "fe", "do outro").statusCode()).isEqualTo(201);
		assertThat(curtir(alvo.nav(), "fe").statusCode()).isEqualTo(200);
		assertThat(admin.nav().post("/api/admin/blog/banimentos", "{\"usuarioId\":" + alvo.id() + ",\"motivo\":\"motivo\"}").statusCode()).isEqualTo(201);
		var res = super_.nav().delete("/api/admin/usuarios/" + alvo.id());
		assertThat(res.statusCode()).isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from blog_comentario where usuario_id = ?", Long.class, alvo.id())).isZero();
		assertThat(quantos("blog_curtida")).isZero();
		assertThat(quantos("blog_banimento")).isZero();
		assertThat(quantos("blog_comentario")).isEqualTo(1); // o da outra pessoa fica
		assertThat(posts.count()).isEqualTo(1);
	}

	@Test
	void apagarOAdminQueBaniuMantemOBanSemBanidoPor() throws Exception {
		var super_ = logado("Super", Papel.SUPERUSUARIO);
		var admin = logado("Admin", Papel.ADMIN);
		var admin2 = logado("Admin Dois", Papel.ADMIN);
		var alvo = logado();
		assertThat(admin.nav().post("/api/admin/blog/banimentos", "{\"usuarioId\":" + alvo.id() + ",\"motivo\":\"motivo\"}").statusCode()).isEqualTo(201);
		assertThat(super_.nav().delete("/api/admin/usuarios/" + admin.id()).statusCode()).isEqualTo(204);
		assertThat(banimentos.estaBanido(alvo.id())).isTrue();
		var lista = json(admin2.nav().get("/api/admin/blog/banimentos?usuarioId=" + alvo.id()));
		assertThat(lista.get("content")).hasSize(1);
		assertThat(lista.get("content").get(0).get("banidoPorEmail").isNull()).isTrue();
		assertThat(lista.get("content").get(0).get("motivo").asString()).isEqualTo("motivo");
	}
}
