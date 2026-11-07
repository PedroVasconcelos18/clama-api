package me.clama.api.freemium;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Tabela freemium_freemiumblacklist criada pelo Django; nenhuma mudanca de schema. Uma linha por confirmacao
 * bem-sucedida; cpf_hash e email_hash sao UNIQUE (a 2a confirmacao do mesmo CPF ou e-mail estoura). device_hash e o
 * valor cru do FingerprintJS, nao um HMAC.
 */
@Entity
@Table(name = "freemium_freemiumblacklist")
public class FreemiumBlacklist {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "cpf_hash")
    private String cpfHash;

    @Column(name = "email_hash")
    private String emailHash;

    @Column(name = "telefone_hash")
    private String telefoneHash;

    @Column(name = "device_hash")
    private String deviceHash;

    @Column(name = "ip_hash")
    private String ipHash;

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

    public String getCpfHash() {
        return cpfHash;
    }

    public void setCpfHash(String v) {
        cpfHash = v;
    }

    public String getEmailHash() {
        return emailHash;
    }

    public void setEmailHash(String v) {
        emailHash = v;
    }

    public String getTelefoneHash() {
        return telefoneHash;
    }

    public void setTelefoneHash(String v) {
        telefoneHash = v;
    }

    public String getDeviceHash() {
        return deviceHash;
    }

    public void setDeviceHash(String v) {
        deviceHash = v;
    }

    public String getIpHash() {
        return ipHash;
    }

    public void setIpHash(String v) {
        ipHash = v;
    }
}
