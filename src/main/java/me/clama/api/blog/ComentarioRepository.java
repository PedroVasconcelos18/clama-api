package me.clama.api.blog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ComentarioRepository extends JpaRepository<Comentario, UUID> {
    long countByPostEspelho_Id(UUID espelho);

    /** Ordering -created_at (o id so desempata, para a paginacao nao repetir nem pular), com o autor junto. */
    @Query("select c from Comentario c join fetch c.customer where c.postEspelho.id = ?1 order by c.createdAt desc, c.id")
    List<Comentario> doEspelho(UUID espelho, Pageable pagina);

    /**
     * Admin: todos (ou so os suspeitos), -created_at; o id desempata para a paginacao nao repetir nem pular. slug do
     * espelho "" = sem filtro (slug real nunca e vazio); o espelho vem no join para o item nao fazer N+1.
     */
    @Query("select c from Comentario c join fetch c.customer join fetch c.postEspelho"
            + " where (?1 = false or c.suspeito = true) and (?2 = '' or c.postEspelho.slug = ?2)"
            + " order by c.createdAt desc, c.id")
    List<Comentario> doAdmin(boolean soSuspeitos, String slugDoEspelho, Pageable pagina);

    @Query("select count(c) from Comentario c where (?1 = false or c.suspeito = true)"
            + " and (?2 = '' or c.postEspelho.slug = ?2)")
    long contarDoAdmin(boolean soSuspeitos, String slugDoEspelho);
}
