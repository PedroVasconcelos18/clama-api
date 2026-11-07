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

    /** Admin: todos (ou so os suspeitos), -created_at; o id desempata para a paginacao nao repetir nem pular. */
    @Query("select c from Comentario c join fetch c.customer where (?1 = false or c.suspeito = true)"
            + " order by c.createdAt desc, c.id")
    List<Comentario> doAdmin(boolean soSuspeitos, Pageable pagina);

    @Query("select count(c) from Comentario c where (?1 = false or c.suspeito = true)")
    long contarDoAdmin(boolean soSuspeitos);
}
