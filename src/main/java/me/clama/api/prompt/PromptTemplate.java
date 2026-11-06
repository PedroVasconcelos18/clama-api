package me.clama.api.prompt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Tabela prompts_prompttemplate criada pelo Django (nome UNIQUE); nenhuma mudanca de schema. */
@Entity
@Table(name = "prompts_prompttemplate")
public class PromptTemplate {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    private String nome;
    private int versao;

    @Column(name = "system_prompt")
    private String systemPrompt;

    /** JSON qualquer (JSONField do Django); texto JSON cru, o jsonb normaliza como no Django. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "instrucoes_por_complexidade")
    private String instrucoesPorComplexidade = "{}";

    private boolean ativo;

    @PrePersist
    void criado() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    /** auto_now: todo save() regrava updated_at. */
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

    public int getVersao() {
        return versao;
    }

    public void setVersao(int v) {
        versao = v;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String v) {
        systemPrompt = v;
    }

    public String getInstrucoesPorComplexidade() {
        return instrucoesPorComplexidade;
    }

    public void setInstrucoesPorComplexidade(String v) {
        instrucoesPorComplexidade = v;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean v) {
        ativo = v;
    }
}
