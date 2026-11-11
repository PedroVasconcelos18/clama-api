package me.clama.blog.interno;

import java.time.Clock;
import java.util.Comparator;
import me.clama.blog.interno.ClienteWordPress.PostWp;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Espelho dos posts (D6). {@link #resolver} le o banco e so vai ao WordPress se o slug nao tem espelho; o GET fica fora
 * de transacao e a gravacao roda em transacao propria ({@code REQUIRES_NEW}), entao a linha sobrevive ao 409 e ao
 * rollback de quem chamou.
 */
@Component
public class Espelho {

	private final PostRepository posts;
	private final ClienteWordPress wordpress;
	private final Clock relogio;
	private final TransactionTemplate novaTransacao;

	Espelho(PostRepository posts, ClienteWordPress wordpress, Clock relogio, PlatformTransactionManager tm) {
		this.posts = posts;
		this.wordpress = wordpress;
		this.relogio = relogio;
		this.novaTransacao = new TransactionTemplate(tm);
		this.novaTransacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * O post que aceita interacao.
	 *
	 * @throws ErroDeNegocio 404 {@code post_nao_encontrado}: o WordPress nao tem o slug publicamente; 409
	 *         {@code post_nao_aceita_interacao}: o espelho existe e nao esta publicado; 503 {@code wordpress_indisponivel}:
	 *         nao deu para perguntar (nada e gravado)
	 */
	public Post resolver(String slug) {
		var post = posts.findFirstBySlugOrderByPublicadoDescPublicadoEmDescWpIdDesc(slug).orElseGet(() -> criar(slug));
		if (!post.isPublicado()) throw new ErroDeNegocio(HttpStatus.CONFLICT, "post_nao_aceita_interacao",
				"Post nao aceita interacao", "Este texto saiu do ar enquanto você escrevia. Copiamos o que você digitou — guarde, e tente de novo quando ele voltar.");
		return post;
	}

	private Post criar(String slug) {
		java.util.List<PostWp> achados;
		try {
			achados = wordpress.buscarPorSlug(slug);
		} catch (ClienteWordPress.Indisponivel e) {
			throw new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "wordpress_indisponivel", e.getMessage(),
					"Estamos com dificuldade de falar com o blog agora.");
		}
		// slug repetido: o publicado (sem senha) vence
		var achado = achados.stream().min(Comparator.comparing(PostWp::protegido)).orElseThrow(() -> new ErroDeNegocio(
				HttpStatus.NOT_FOUND, "post_nao_encontrado", "Post nao encontrado", "Não encontramos este texto. Ele pode ter saído do ar."));
		return novaTransacao.execute(s -> {
			gravar(achado);
			return posts.findById(achado.id()).orElseThrow();
		});
	}

	/** Cria ou atualiza o espelho de um post; 1 se gravou, 0 se ja estava igual. Usado tambem pela reconciliacao. */
	int gravar(PostWp p) {
		return posts.upsert(p.id(), p.slug(), p.titulo(), p.url(), p.publicado(), p.publicadoEm(), relogio.instant());
	}
}
