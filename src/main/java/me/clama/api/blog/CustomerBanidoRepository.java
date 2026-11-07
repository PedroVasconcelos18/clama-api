package me.clama.api.blog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CustomerBanidoRepository extends JpaRepository<CustomerBanido, UUID> {
    /** Ban ativo: CustomerBanido.objects.filter(customer=user, revogado_em__isnull=True).exists(). */
    boolean existsByCustomer_IdAndRevogadoEmIsNull(Long customerId);

    /** filter(customer_id, revogado_em__isnull=True).first() (o mais recente). */
    Optional<CustomerBanido> findFirstByCustomer_IdAndRevogadoEmIsNullOrderByBanidoEmDescIdDesc(Long customerId);

    /** Bans ativos, -banido_em, com o cliente e quem baniu. */
    @Query("select b from CustomerBanido b join fetch b.customer join fetch b.banidoPor where b.revogadoEm is null"
            + " order by b.banidoEm desc, b.id")
    List<CustomerBanido> ativos(Pageable pagina);

    long countByRevogadoEmIsNull();
}
