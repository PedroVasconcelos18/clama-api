package me.clama.api.identidade.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "token_blacklist_blacklistedtoken")
public class TokenRevogado {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "blacklisted_at")
    OffsetDateTime blacklistedAt = OffsetDateTime.now();

    @Column(name = "token_id")
    Long tokenId;

    protected TokenRevogado() {}

    TokenRevogado(Long tokenId) {
        this.tokenId = tokenId;
    }
}
