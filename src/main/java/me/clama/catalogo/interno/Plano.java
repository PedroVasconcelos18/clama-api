package me.clama.catalogo.interno;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import me.clama.catalogo.Complexidade;
import me.clama.catalogo.PlanoResumo;
import me.clama.catalogo.TipoDePlano;

@Entity
@Table(name = "plano")
public class Plano {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String nome;
	private String descricao;
	private long valorCentavos;

	@Enumerated(EnumType.STRING)
	private Complexidade complexidade;

	@Enumerated(EnumType.STRING)
	private TipoDePlano tipo;

	private int ordem;
	private boolean ativo;

	protected Plano() {}

	/** Plano de vitrine; os tecnicos nascem na migration. */
	public static Plano comum(String nome, String descricao, long valorCentavos, Complexidade complexidade, int ordem) {
		if (valorCentavos < 1) throw new IllegalArgumentException("Plano comum exige valor de pelo menos 1 centavo.");
		var p = new Plano();
		p.nome = nome;
		p.descricao = descricao == null ? "" : descricao;
		p.valorCentavos = valorCentavos;
		p.complexidade = complexidade;
		p.tipo = TipoDePlano.COMUM;
		p.ordem = ordem;
		p.ativo = true;
		return p;
	}

	public Long getId() { return id; }
	public String getNome() { return nome; }
	public String getDescricao() { return descricao; }
	public long getValorCentavos() { return valorCentavos; }
	public Complexidade getComplexidade() { return complexidade; }
	public TipoDePlano getTipo() { return tipo; }
	public int getOrdem() { return ordem; }
	public boolean isAtivo() { return ativo; }
	public boolean isTecnico() { return tipo != TipoDePlano.COMUM; }

	/** Nome e descricao: unico que vale para todo tipo. */
	public void renomear(String nome, String descricao) {
		this.nome = nome;
		this.descricao = descricao == null ? "" : descricao;
	}

	public void alterarComplexidade(Complexidade complexidade) {
		exigirComum("complexidade");
		this.complexidade = complexidade;
	}

	public void alterarValor(long valorCentavos) {
		exigirComum("valor");
		if (valorCentavos < 1) throw new IllegalArgumentException("Plano comum exige valor de pelo menos 1 centavo.");
		this.valorCentavos = valorCentavos;
	}

	public void reordenar(int ordem) {
		exigirComum("ordem");
		this.ordem = ordem;
	}

	/** O tipo nasce com o plano; so existe para recusar com mensagem clara. */
	public void alterarTipo(TipoDePlano novo) {
		if (novo != tipo) throw new PlanoTecnicoException("O tipo do plano nao muda.");
	}

	public void ativar() {
		this.ativo = true;
	}

	public void desativar() {
		exigirComum("ativo");
		this.ativo = false;
	}

	private void exigirComum(String campo) {
		if (isTecnico()) throw new PlanoTecnicoException("Plano tecnico nao aceita mudar " + campo + ".");
	}

	public PlanoResumo resumo() {
		return new PlanoResumo(id, nome, descricao, valorCentavos, complexidade, tipo);
	}
}
