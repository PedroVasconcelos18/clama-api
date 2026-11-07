package me.clama.api.blog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerBanidoRepository extends JpaRepository<CustomerBanido, UUID> {
}
