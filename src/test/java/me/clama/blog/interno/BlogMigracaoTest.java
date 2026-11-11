package me.clama.blog.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.clama.BancoDeTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V7: os CHECKs e as regras de exclusao de usuario (CASCADE e SET NULL) valem no banco. */
@SpringBootTest
@Import(BancoDeTeste.class)
class BlogMigracaoTest {

	@Autowired JdbcTemplate jdbc;
	long autor, admin;

	@BeforeEach
	@AfterEach
	void limpar() {
		jdbc.update("delete from blog_banimento");
		jdbc.update("delete from blog_curtida");
		jdbc.update("delete from blog_comentario");
		jdbc.update("delete from blog_post");
		jdbc.update("delete from usuario where email like 'bmig-%'");
	}

	long usuario(String email) {
		return jdbc.queryForObject("""
				insert into usuario (email, email_canonico, nome_completo, papel, ativo, criado_em)
				values (?, ?, 'N', 'CLIENTE', true, now()) returning id""", Long.class, email, email);
	}

	void post() {
		jdbc.update("insert into blog_post (wp_id, slug, publicado, atualizado_em) values (7, 'fe', true, now())");
	}

	void comentar(String conteudo) {
		jdbc.update("insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, criado_em) values (7, ?, ?, false, now())", autor, conteudo);
	}

	@Test
	void checkDoConteudoDoComentarioContaCodePoints() {
		autor = usuario("bmig-a@exemplo.com");
		post();
		assertThatThrownBy(() -> comentar("ab")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> comentar("x".repeat(2001))).isInstanceOf(DataIntegrityViolationException.class);
		comentar("abc");
		comentar("😀".repeat(2000)); // 2000 code points, 4000 chars UTF-16
		assertThat(jdbc.queryForObject("select count(*) from blog_comentario", Integer.class)).isEqualTo(2);
	}

	@Test
	void checkDoMotivoEDoWpId() {
		autor = usuario("bmig-a@exemplo.com");
		assertThatThrownBy(() -> jdbc.update("insert into blog_banimento (usuario_id, motivo, banido_em) values (?, 'ab', now())", autor))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("insert into blog_post (wp_id, slug, publicado, atualizado_em) values (0, 'x', true, now())"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void apagarUsuarioLimpaComentarioCurtidaEBanRecebido() {
		autor = usuario("bmig-a@exemplo.com");
		post();
		comentar("um comentario");
		jdbc.update("insert into blog_curtida (post_id, usuario_id, criado_em) values (7, ?, now())", autor);
		jdbc.update("insert into blog_banimento (usuario_id, motivo, banido_em) values (?, 'motivo', now())", autor);
		jdbc.update("delete from usuario where id = ?", autor);
		for (var t : new String[] { "blog_comentario", "blog_curtida", "blog_banimento" })
			assertThat(jdbc.queryForObject("select count(*) from " + t, Integer.class)).as(t).isZero();
		assertThat(jdbc.queryForObject("select count(*) from blog_post", Integer.class)).isEqualTo(1);
	}

	@Test
	void apagarQuemBaniuMantemOBanComBanidoPorNulo() {
		autor = usuario("bmig-a@exemplo.com");
		admin = usuario("bmig-adm@exemplo.com");
		jdbc.update("insert into blog_banimento (usuario_id, motivo, banido_por_id, banido_em) values (?, 'motivo', ?, now())", autor, admin);
		jdbc.update("delete from usuario where id = ?", admin);
		assertThat(jdbc.queryForObject("select banido_por_id from blog_banimento where usuario_id = ?", Long.class, autor)).isNull();
		assertThat(jdbc.queryForObject("select count(*) from blog_banimento", Integer.class)).isEqualTo(1);
	}

	@Test
	void postComComentarioNaoPodeSerApagado() {
		autor = usuario("bmig-a@exemplo.com");
		post();
		comentar("um comentario");
		assertThatThrownBy(() -> jdbc.update("delete from blog_post where wp_id = 7")).isInstanceOf(DataIntegrityViolationException.class);
	}
}
