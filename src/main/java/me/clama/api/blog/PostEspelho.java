package me.clama.api.blog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * blog_postespelho: o post do WordPress que o Django espelha. Identidade e wp_post_id (unique); slug nao e unique.
 * post_legado_id (FK de blog_post, ignorada: R1) e so lido, nunca escrito. Nunca apaga (o repositorio nao expoe delete).
 */
@Entity
@Table(name = "blog_postespelho")
public class PostEspelho extends EntidadeDoBlog {
    public static final String RASCUNHO = "rascunho";
    public static final String PUBLICADO = "publicado";

    @Column(name = "wp_post_id")
    private int wpPostId;

    private String slug;

    private String titulo;

    private String status = RASCUNHO;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    private String url = "";

    @Column(name = "post_legado_id", insertable = false, updatable = false)
    private UUID postLegadoId;

    /** aceita_interacao: so post publicado recebe comentario e curtida. */
    public boolean aceitaInteracao() {
        return PUBLICADO.equals(status);
    }

    public int getWpPostId() {
        return wpPostId;
    }

    public void setWpPostId(int v) {
        wpPostId = v;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String v) {
        slug = v;
    }

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String v) {
        titulo = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime v) {
        publishedAt = v;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String v) {
        url = v;
    }

    public UUID getPostLegadoId() {
        return postLegadoId;
    }
}
