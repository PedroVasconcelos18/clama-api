package me.clama.api.freemium;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import me.clama.api.pedido.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface TokenDeConfirmacaoRepository extends JpaRepository<TokenDeConfirmacao, UUID> {
    /**
     * select_for_update().select_related("pedido"): trava a linha do token e depois a do pedido (o Hibernate so trava
     * a raiz de um join fetch, entao sao dois selects; o pedido e lido ja travado, sem join fetch, para nao devolver
     * estado lido antes do lock). Mesmo lock_timeout do PedidoRepository.travar.
     */
    default Optional<TokenDeConfirmacao> travarComPedido(String token) {
        Optional<TokenDeConfirmacao> t = travarToken(token);
        t.ifPresent(x -> travarPedido(x.getPedido().getId()));
        return t;
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "15000"))
    @Query("select t from TokenDeConfirmacao t where t.token = ?1")
    Optional<TokenDeConfirmacao> travarToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "15000"))
    @Query("select p from Pedido p where p.id = ?1")
    Optional<Pedido> travarPedido(UUID id);

    /** O token do pedido (so o submit cria token, entao e um so). */
    @Query("select t from TokenDeConfirmacao t where t.pedido.id = ?1")
    Optional<TokenDeConfirmacao> doPedido(UUID pedidoId);
}
