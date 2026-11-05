package me.clama.api.identidade.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "token_blacklist_outstandingtoken")
public class TokenEmitido {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "token")
    String token;

    @Column(name = "created_at")
    OffsetDateTime createdAt;

    @Column(name = "expires_at")
    OffsetDateTime expiresAt;

    @Column(name = "user_id")
    Long userId;

    @Column(name = "jti")
    String jti;

    protected TokenEmitido() {}

    TokenEmitido(String jti, String token, OffsetDateTime createdAt, OffsetDateTime expiresAt, Long userId) {
        this.jti = jti;
        this.token = token;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public String getJti() {
        return jti;
    }

    public Long getUserId() {
        return userId;
    }
}
