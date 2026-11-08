package me.clama.conta;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "usuario")
public class Usuario {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String email;

	@Column(name = "email_canonico")
	private String emailCanonico;

	/** Hash do PasswordEncoder; nulo = sem senha utilizavel. */
	private String senha;

	@Column(name = "nome_completo")
	private String nomeCompleto;

	@Column(name = "nome_formato_blog")
	private String nomeFormatoBlog;

	@Enumerated(EnumType.STRING)
	private Papel papel;

	private boolean ativo;

	@Column(name = "criado_em")
	private Instant criadoEm;

	@Column(name = "ultimo_login_em")
	private Instant ultimoLoginEm;

	protected Usuario() {}

	public static Usuario novo(String email, String nomeCompleto, Papel papel, Instant agora) {
		var u = new Usuario();
		u.email = email.strip().toLowerCase(Locale.ROOT);
		u.emailCanonico = EmailCanonico.de(email);
		u.nomeCompleto = nomeCompleto == null ? "" : nomeCompleto;
		u.nomeFormatoBlog = "compacto";
		u.papel = papel;
		u.ativo = true;
		u.criadoEm = agora;
		return u;
	}

	public Long getId() { return id; }
	public String getEmail() { return email; }
	public String getEmailCanonico() { return emailCanonico; }
	public String getSenha() { return senha; }
	public String getNomeCompleto() { return nomeCompleto; }
	public String getNomeFormatoBlog() { return nomeFormatoBlog; }
	public Papel getPapel() { return papel; }
	public boolean isAtivo() { return ativo; }
	public Instant getCriadoEm() { return criadoEm; }
	public Instant getUltimoLoginEm() { return ultimoLoginEm; }
	public boolean temSenha() { return senha != null; }

	public void definirSenha(String hash) { this.senha = hash; }
	public void registrarLogin(Instant quando) { this.ultimoLoginEm = quando; }
	public void renomear(String nomeCompleto) { this.nomeCompleto = nomeCompleto; }

	public void escolherFormatoBlog(String formato) {
		if (!formato.equals("compacto") && !formato.equals("completo"))
			throw new IllegalArgumentException("formato invalido: " + formato);
		this.nomeFormatoBlog = formato;
	}

	public void promover(Papel papel) { this.papel = papel; }
	public void desativar() { this.ativo = false; }
	public void reativar() { this.ativo = true; }
}
