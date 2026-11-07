package me.clama.api.identidade;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    @Query("select u from Usuario u where lower(u.email) = lower(?1)")
    List<Usuario> buscarPorEmailIgnorandoCaixa(String email);

    /** So os ids: quem trava carrega a linha depois, sem entidade velha no contexto de persistencia. */
    @Query("select u.id from Usuario u where lower(u.email) = lower(?1)")
    List<Long> idsPorEmailIgnorandoCaixa(String email);

    /** User.objects.filter(Q(email__iexact=email) | Q(email_hash=hash)), na ordem do .first(). */
    @Query("select u from Usuario u where lower(u.email) = lower(?1) or u.emailHash = ?2 order by u.id")
    List<Usuario> buscarPorEmailOuHash(String email, String emailHash);

    /** select_for_update().get(pk=...). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.id = ?1")
    Optional<Usuario> travar(Long id);
}
