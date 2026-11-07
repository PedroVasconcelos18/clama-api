package me.clama.api.blog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReacaoRepository extends JpaRepository<Reacao, UUID> {
}
