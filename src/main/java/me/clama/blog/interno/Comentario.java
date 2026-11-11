package me.clama.blog.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import me.clama.infra.cripto.CampoCifrado;

/** Comentario de um post. O IP (so para o Marco Civil) fica cifrado e e zerado depois de 180 dias. */
@Entity
@Table(name = "blog_comentario")
public class Comentario {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "post_id")
	private Long postId;

	@Column(name = "usuario_id")
	private Long usuarioId;

	private String conteudo;
	private boolean suspeito;

	@Convert(converter = CampoCifrado.class)
	@Column(name = "ip_cifrado")
	private String ip;

	@Column(name = "criado_em")
	private Instant criadoEm;

	protected Comentario() {}

	Comentario(Long postId, Long usuarioId, String conteudo, boolean suspeito, String ip, Instant criadoEm) {
		this.postId = postId;
		this.usuarioId = usuarioId;
		this.conteudo = conteudo;
		this.suspeito = suspeito;
		this.ip = ip;
		this.criadoEm = criadoEm;
	}

	public Long getId() { return id; }
	public Long getPostId() { return postId; }
	public Long getUsuarioId() { return usuarioId; }
	public String getConteudo() { return conteudo; }
	public boolean isSuspeito() { return suspeito; }
	/** IP em claro (decifrado), ou nulo depois da purga. */
	public String getIp() { return ip; }
	public Instant getCriadoEm() { return criadoEm; }
}
