package me.clama.pagamento.interno;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CobrancaRepository extends JpaRepository<Cobranca, Long> {

	/** A cobranca mais recente (maior n) do pedido. */
	Optional<Cobranca> findFirstByPedidoIdOrderByNDesc(UUID pedidoId);

	boolean existsByPedidoIdAndMpPagamentoId(UUID pedidoId, long mpPagamentoId);
}
