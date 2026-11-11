package me.clama.blog.interno;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PostRepository extends JpaRepository<Post, Long> {

	/** Slug repetido: o publicado primeiro, depois o mais recente. */
	Optional<Post> findFirstBySlugOrderByPublicadoDescPublicadoEmDescWpIdDesc(String slug);

	/**
	 * Cria ou atualiza pela identidade ({@code wp_id}); so grava se algum campo mudou (1 = gravou, 0 = igual). Duas
	 * criacoes simultaneas do mesmo post terminam numa linha so, sem excecao.
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			insert into blog_post (wp_id, slug, titulo, url, publicado, publicado_em, atualizado_em)
			values (:wpId, :slug, :titulo, :url, :publicado, :publicadoEm, :agora)
			on conflict (wp_id) do update set slug = excluded.slug, titulo = excluded.titulo, url = excluded.url,
				publicado = excluded.publicado, publicado_em = excluded.publicado_em, atualizado_em = excluded.atualizado_em
			where (blog_post.slug, blog_post.titulo, blog_post.url, blog_post.publicado, blog_post.publicado_em)
				is distinct from (excluded.slug, excluded.titulo, excluded.url, excluded.publicado, excluded.publicado_em)""")
	int upsert(long wpId, String slug, String titulo, String url, boolean publicado, Instant publicadoEm, Instant agora);

	/** Post que sumiu da lista publica: deixa de aceitar interacao. So grava os que estavam publicados. */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Post p set p.publicado = false, p.atualizadoEm = :agora where p.publicado = true and p.wpId in :ids")
	int despublicar(java.util.Collection<Long> ids, Instant agora);
}
