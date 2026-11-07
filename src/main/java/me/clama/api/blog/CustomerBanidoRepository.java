package me.clama.api.blog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerBanidoRepository extends JpaRepository<CustomerBanido, UUID> {
    /** Ban ativo: CustomerBanido.objects.filter(customer=user, revogado_em__isnull=True).exists(). */
    boolean existsByCustomer_IdAndRevogadoEmIsNull(Long customerId);
}
