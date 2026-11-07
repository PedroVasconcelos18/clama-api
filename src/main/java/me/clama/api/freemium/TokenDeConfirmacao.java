package me.clama.api.freemium;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import me.clama.api.pedido.Pedido;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Tabela freemium_freemiumconfirmationtoken criada pelo Django; nenhuma mudanca de schema. Token opaco do double
 * opt-in: em texto claro (paridade de schema), single-use (used_at), TTL de 24h calculado por quem cria. Um token por
 * pedido.
 */
@Entity
@Table(name = "freemium_freemiumconfirmationtoken")
public class TokenDeConfirmacao {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id")
    private Pedido pedido;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "ip_origem", columnDefinition = "inet")
    @ColumnTransformer(read = "host(ip_origem)", write = "cast(? as inet)")
    private String ipOrigem;

    @Column(name = "device_hash")
    private String deviceHash = "";

    @PrePersist
    void criado() {
        createdAt = updatedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void atualizado() {
        updatedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String v) {
        token = v;
    }

    public Pedido getPedido() {
        return pedido;
    }

    public void setPedido(Pedido v) {
        pedido = v;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime v) {
        expiresAt = v;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(OffsetDateTime v) {
        usedAt = v;
    }

    public String getIpOrigem() {
        return ipOrigem;
    }

    public void setIpOrigem(String v) {
        ipOrigem = v;
    }

    public String getDeviceHash() {
        return deviceHash;
    }

    public void setDeviceHash(String v) {
        deviceHash = v == null ? "" : v;
    }
}
