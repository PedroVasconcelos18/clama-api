package me.clama.blog.interno;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComentarioRepository extends JpaRepository<Comentario, Long> {

	long countByPostId(Long postId);

	/** Os 20 mais novos do post (o widget nao pagina). */
	List<Comentario> findTop20ByPostIdOrderByCriadoEmDescIdDesc(Long postId);
}
