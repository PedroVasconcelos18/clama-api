package me.clama.api.identidade.token;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TokenEmitidoRepository extends JpaRepository<TokenEmitido, Long> {
    Optional<TokenEmitido> findByJti(String jti);

    @Modifying
    @Query(value = "insert into token_blacklist_outstandingtoken (jti, token, created_at, expires_at, user_id) values (?1, ?2, ?3, ?4, ?5) on conflict (jti) do nothing", nativeQuery = true)
    void inserirSeFaltar(String jti, String token, OffsetDateTime createdAt, OffsetDateTime expiresAt, Long userId);
}
