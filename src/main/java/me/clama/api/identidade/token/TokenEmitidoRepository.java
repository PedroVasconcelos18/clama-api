package me.clama.api.identidade.token;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TokenEmitidoRepository extends JpaRepository<TokenEmitido, Long> {
    Optional<TokenEmitido> findByJti(String jti);

    @Query("select t from TokenEmitido t where t.userId = ?1 and t.id not in (select r.tokenId from TokenRevogado r)")
    List<TokenEmitido> naoRevogadosDoUsuario(Long userId);
}
