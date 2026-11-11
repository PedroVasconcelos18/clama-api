package me.clama.blog.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Espelho de um post do WordPress. Escrito so por {@link PostRepository#upsert}; nunca apagado. */
@Entity
@Table(name = "blog_post")
public class Post {

	@Id
	@Column(name = "wp_id")
	private Long wpId;

	private String slug;
	private String titulo;
	private String url;

	/** Publicado e sem senha: o unico estado que aceita comentario e curtida. */
	private boolean publicado;

	@Column(name = "publicado_em")
	private Instant publicadoEm;

	@Column(name = "atualizado_em")
	private Instant atualizadoEm;

	protected Post() {}

	public Long getWpId() { return wpId; }
	public String getSlug() { return slug; }
	public String getTitulo() { return titulo; }
	public String getUrl() { return url; }
	public boolean isPublicado() { return publicado; }
	public Instant getPublicadoEm() { return publicadoEm; }
	public Instant getAtualizadoEm() { return atualizadoEm; }
}
