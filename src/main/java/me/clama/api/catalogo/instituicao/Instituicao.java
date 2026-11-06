package me.clama.api.catalogo.instituicao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Tabela instituicoes_instituicao criada pelo Django; sem Repasse (etapa propria). logo = data URI ou "". */
@Entity
@Table(name = "instituicoes_instituicao")
public class Instituicao {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    private String nome;
    private String logo = "";
    private boolean ativo = true;
    private int ordem = 0;

    @PrePersist
    void criado() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    /** auto_now: todo save() do Django regrava updated_at. */
    public void tocar() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String v) {
        nome = v;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String v) {
        logo = v;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean v) {
        ativo = v;
    }

    public int getOrdem() {
        return ordem;
    }

    public void setOrdem(int v) {
        ordem = v;
    }
}
