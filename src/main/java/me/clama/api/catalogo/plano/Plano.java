package me.clama.api.catalogo.plano;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Tabela plans_plan criada pelo Django; nenhuma mudanca de schema. */
@Entity
@Table(name = "plans_plan")
public class Plano {
    public static final java.util.List<String> COMPLEXIDADES = java.util.List.of("simples", "com_versiculo",
            "com_profecia_e_versiculos", "simples_gratuita");

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    private String nome;

    @Column(name = "valor_centavos")
    private int valorCentavos;

    private String descricao;
    private String complexidade = "simples";
    private short ordem = 1;
    private boolean ativo = true;
    private boolean visivel = true;

    @PrePersist
    void criado() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    /** auto_now: todo save() do Django regrava updated_at, mesmo sem mudanca. */
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

    public int getValorCentavos() {
        return valorCentavos;
    }

    public void setValorCentavos(int v) {
        valorCentavos = v;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String v) {
        descricao = v;
    }

    public String getComplexidade() {
        return complexidade;
    }

    public void setComplexidade(String v) {
        complexidade = v;
    }

    public int getOrdem() {
        return ordem;
    }

    public void setOrdem(int v) {
        ordem = (short) v;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean v) {
        ativo = v;
    }

    public boolean isVisivel() {
        return visivel;
    }

    public void setVisivel(boolean v) {
        visivel = v;
    }
}
