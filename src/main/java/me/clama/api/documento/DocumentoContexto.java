package me.clama.api.documento;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tabela documents_documentocontexto criada pelo Django. O id nasce antes do INSERT (default=uuid4), porque o caminho
 * do arquivo (documents/<id>/<nome>) depende dele.
 */
@Entity
@Table(name = "documents_documentocontexto")
public class DocumentoContexto {
    @Id
    private UUID id;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    private String nome;
    private String descricao = "";
    /** caminho relativo ao MEDIA_ROOT (FileField.name). */
    private String arquivo;

    @Column(name = "tipo_mime")
    private String tipoMime;

    @Column(name = "tamanho_bytes")
    private int tamanhoBytes;

    @Column(name = "anthropic_file_id")
    private String anthropicFileId;

    @Column(name = "data_sincronizacao")
    private OffsetDateTime dataSincronizacao;

    private boolean ativo;

    protected DocumentoContexto() {}

    public DocumentoContexto(UUID id) {
        this.id = id;
    }

    @PrePersist
    void criado() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    /** auto_now: todo save() regrava updated_at. */
    public void tocar() {
        updatedAt = OffsetDateTime.now();
    }

    /** esta_sincronizado: bool(anthropic_file_id). */
    public boolean sincronizado() {
        return anthropicFileId != null && !anthropicFileId.isEmpty();
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

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String v) {
        descricao = v;
    }

    public String getArquivo() {
        return arquivo;
    }

    public void setArquivo(String v) {
        arquivo = v;
    }

    public String getTipoMime() {
        return tipoMime;
    }

    public void setTipoMime(String v) {
        tipoMime = v;
    }

    public int getTamanhoBytes() {
        return tamanhoBytes;
    }

    public void setTamanhoBytes(int v) {
        tamanhoBytes = v;
    }

    public String getAnthropicFileId() {
        return anthropicFileId;
    }

    public OffsetDateTime getDataSincronizacao() {
        return dataSincronizacao;
    }

    /** sincronizar_documento: file_id e a hora da sincronizacao. */
    public void sincronizar(String fileId) {
        anthropicFileId = fileId;
        dataSincronizacao = OffsetDateTime.now();
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean v) {
        ativo = v;
    }
}
