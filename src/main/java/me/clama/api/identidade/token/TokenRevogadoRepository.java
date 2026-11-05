package me.clama.api.identidade.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TokenRevogadoRepository extends JpaRepository<TokenRevogado, Long> {
    @Query("select count(r) > 0 from TokenRevogado r where r.tokenId in (select t.id from TokenEmitido t where t.jti = ?1)")
    boolean existePorJti(String jti);

    boolean existsByTokenId(Long tokenId);
}
