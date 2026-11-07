package me.clama.api.pedido;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface PedidoRepository extends JpaRepository<Pedido, UUID> {
    /** select_for_update().get(id=...). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // Hibernate 7 + Postgres emitem "set local lock_timeout = 15000" antes do for update (e zeram depois): sem isso a
    // fila no lock de um pedido seguraria a conexao do pool ate o MP responder.
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "15000"))
    @Query("select p from Pedido p where p.id = ?1")
    Optional<Pedido> travar(UUID id);

    /** Pedidos do usuario, mais recentes primeiro, com o plano. Os limites sao inclusivos. */
    @Query("select p from Pedido p join fetch p.plano where p.user.id = ?1 and p.createdAt >= ?2 and p.createdAt <= ?3 "
            + "order by p.createdAt desc")
    java.util.List<Pedido> doUsuario(Long usuario, java.time.OffsetDateTime de, java.time.OffsetDateTime ate);
}
