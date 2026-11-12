package me.clama.blog.interno;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComentarioRepository extends JpaRepository<Comentario, Long> {

	long countByPostId(Long postId);

	/** Com o IP decifrado (so a LGPD pede). */
	List<Comentario> findByUsuarioIdOrderByCriadoEmAscIdAsc(Long usuarioId);

	/** Os 20 mais novos do post (o widget nao pagina), numa projecao: a coluna do IP nem e lida, quanto menos decifrada. */
	<T> List<T> findTop20ByPostIdOrderByCriadoEmDescIdDesc(Long postId, Class<T> projecao);

	/** So o que a lista publica mostra. */
	interface Linha {
		Long getId();
		Long getUsuarioId();
		String getConteudo();
		java.time.Instant getCriadoEm();
	}
}
