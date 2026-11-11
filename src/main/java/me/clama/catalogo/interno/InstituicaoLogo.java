package me.clama.catalogo.interno;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Imagem da instituicao, em tabela propria para as listas nao carregarem blobs. */
@Entity
@Table(name = "instituicao_logo")
public class InstituicaoLogo {

	@Id
	private Long instituicaoId;

	private byte[] conteudo;
	private String mime;
	private String sha256;
	private Instant atualizadoEm;

	protected InstituicaoLogo() {}

	public InstituicaoLogo(Long instituicaoId, byte[] conteudo, String mime, String sha256, Instant atualizadoEm) {
		this.instituicaoId = instituicaoId;
		this.conteudo = conteudo;
		this.mime = mime;
		this.sha256 = sha256;
		this.atualizadoEm = atualizadoEm;
	}

	public Long getInstituicaoId() { return instituicaoId; }
	public byte[] getConteudo() { return conteudo; }
	public String getMime() { return mime; }
	public String getSha256() { return sha256; }
	public Instant getAtualizadoEm() { return atualizadoEm; }
}
