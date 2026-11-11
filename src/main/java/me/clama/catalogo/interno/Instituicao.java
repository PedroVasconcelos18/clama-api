package me.clama.catalogo.interno;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import me.clama.catalogo.InstituicaoResumo;

@Entity
@Table(name = "instituicao")
public class Instituicao {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String nome;
	private int ordem;
	private boolean ativo;

	protected Instituicao() {}

	public static Instituicao nova(String nome, int ordem) {
		var i = new Instituicao();
		i.nome = nome;
		i.ordem = ordem;
		i.ativo = true;
		return i;
	}

	public Long getId() { return id; }
	public String getNome() { return nome; }
	public int getOrdem() { return ordem; }
	public boolean isAtivo() { return ativo; }

	public void renomear(String nome) { this.nome = nome; }
	public void reordenar(int ordem) { this.ordem = ordem; }
	public void ativar() { this.ativo = true; }
	public void desativar() { this.ativo = false; }

	public InstituicaoResumo resumo() {
		return new InstituicaoResumo(id, nome);
	}
}
