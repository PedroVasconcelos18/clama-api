package me.clama.api.identidade.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TokenRevogadoRepository extends JpaRepository<TokenRevogado, Long> {
    @Query("select count(r) > 0 from TokenRevogado r where r.tokenId in (select t.id from TokenEmitido t where t.jti = ?1)")
    boolean existePorJti(String jti);

    @Modifying
    @Query(value = "insert into token_blacklist_blacklistedtoken (blacklisted_at, token_id) values (now(), ?1) on conflict (token_id) do nothing", nativeQuery = true)
    void revogar(Long tokenId);

    @Modifying
    @Query(value = "insert into token_blacklist_blacklistedtoken (blacklisted_at, token_id) select now(), id from token_blacklist_outstandingtoken where user_id = ?1 on conflict (token_id) do nothing", nativeQuery = true)
    void revogarTodosDoUsuario(Long userId);
}
