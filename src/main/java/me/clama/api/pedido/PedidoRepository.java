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

    /** Alertas das metricas: pedidos nesses status, mais recentes primeiro, com o plano. */
    @Query("select p from Pedido p join fetch p.plano where p.status in ?1 order by p.createdAt desc, p.id")
    java.util.List<Pedido> alertas(java.util.Collection<StatusPedido> status, org.springframework.data.domain.Pageable pagina);

    /** Itens da lista admin (plano junto); a ordem vem de quem pediu. */
    @Query("select p from Pedido p join fetch p.plano where p.id in ?1")
    java.util.List<Pedido> comPlano(java.util.Collection<UUID> ids);

    /** Gate de pendente do freemium: Pedido.filter(eh_gratuito=True, status=...), so id e identificadores, decifrados. */
    @Query("select new me.clama.api.pedido.IdentificadoresPendentes(p.id, p.email, p.telefone, p.cpfCnpj)"
            + " from Pedido p where p.ehGratuito = true and p.status = ?1")
    java.util.List<IdentificadoresPendentes> pendentesDoFreemium(StatusPedido status);

    /** Busca q do admin: id, nome e e-mail (decifrados) dos pedidos sem conta. */
    @Query("select p.id, p.nome, p.email from Pedido p where p.user is null")
    java.util.List<Object[]> semUsuario();

    /** Detalhe admin: plano e instituicao junto. */
    @Query("select p from Pedido p join fetch p.plano left join fetch p.instituicao where p.id = ?1")
    Optional<Pedido> detalhe(UUID id);
}
