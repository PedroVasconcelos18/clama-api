package me.clama.pedido.interno;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface PedidoRepository extends JpaRepository<Pedido, UUID> {

	/** {@code SELECT ... FOR UPDATE} com espera maxima de 15 s (lock preso vira erro, nao fila eterna). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "15000"))
	@Query("select p from Pedido p where p.id = :id")
	Optional<Pedido> travar(UUID id);

	List<Pedido> findByEmailHmac(String emailHmac);

	/** Pedidos do usuario no intervalo (inclusivo), mais recentes primeiro. */
	List<Pedido> findByUsuarioIdAndCriadoEmBetweenOrderByCriadoEmDesc(Long usuarioId, Instant de, Instant ate);

	@Query("""
			select p.id from Pedido p where p.status in (me.clama.pedido.StatusPedido.PAGO, me.clama.pedido.StatusPedido.GERANDO_ORACAO,
			me.clama.pedido.StatusPedido.ORACAO_GERADA, me.clama.pedido.StatusPedido.AGUARDANDO_NOVA_TENTATIVA)
			and p.atualizadoEm < :antesDe order by p.atualizadoEm""")
	List<UUID> parados(Instant antesDe);
}
