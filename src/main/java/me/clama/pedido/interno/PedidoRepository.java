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

public interface PedidoRepository extends JpaRepository<Pedido, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Pedido> {

	/** {@code SELECT ... FOR UPDATE} com espera maxima de 15 s (lock preso vira erro, nao fila eterna). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "15000"))
	@Query("select p from Pedido p where p.id = :id")
	Optional<Pedido> travar(UUID id);

	/** O que o status publico le: sem PII, sem hidratar o {@code Pedido} (a oracao cifrada so sai por {@link #oracaoDe}). */
	record Status(UUID id, me.clama.pedido.StatusPedido status, Long planoId, Long instituicaoId, long valorCentavos,
			Instant criadoEm, String ultimoErro) {}

	@Query("select new me.clama.pedido.interno.PedidoRepository$Status(p.id, p.status, p.planoId, p.instituicaoId, p.valorCentavos, p.criadoEm, p.ultimoErro) from Pedido p where p.id = :id")
	Optional<Status> status(UUID id);

	/** Decifra so a oracao. */
	@Query("select p.oracao from Pedido p where p.id = :id")
	Optional<String> oracaoDe(UUID id);

	List<Pedido> findByEmailHmac(String emailHmac);

	/** Pedidos do usuario no intervalo (inclusivo), mais recentes primeiro. */
	List<Pedido> findByUsuarioIdAndCriadoEmBetweenOrderByCriadoEmDesc(Long usuarioId, Instant de, Instant ate);

	@Query("""
			select p.id from Pedido p where p.status in (me.clama.pedido.StatusPedido.PAGO, me.clama.pedido.StatusPedido.GERANDO_ORACAO,
			me.clama.pedido.StatusPedido.ORACAO_GERADA, me.clama.pedido.StatusPedido.AGUARDANDO_NOVA_TENTATIVA)
			and p.atualizadoEm < :antesDe order by p.atualizadoEm""")
	List<UUID> parados(Instant antesDe);
}
