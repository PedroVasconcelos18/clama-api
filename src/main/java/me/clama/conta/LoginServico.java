package me.clama.conta;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Confere e-mail e senha; a sessao fica com o controller. Toda falha e o mesmo 401 generico. */
@Service
public class LoginServico {

	static final String MENSAGEM = "Email ou senha inválidos.";

	private final UsuarioRepository usuarios;
	private final PasswordEncoder senhas;
	private final LimiteDeTaxa limite;
	private final String hashFicticio;

	LoginServico(UsuarioRepository usuarios, PasswordEncoder senhas, LimiteDeTaxa limite) {
		this.usuarios = usuarios;
		this.senhas = senhas;
		this.limite = limite;
		this.hashFicticio = senhas.encode("hash-ficticio-para-igualar-o-tempo");
	}

	@Transactional
	public Usuario autenticar(String email, String senha, String ip) {
		var emailMinusculo = email.strip().toLowerCase(Locale.ROOT);
		limite.exigir(new Cota("login:email:" + emailMinusculo, 5, Duration.ofMinutes(15)),
				new Cota("login:ip:" + ip, 30, Duration.ofMinutes(1)));

		var usuario = usuarios.findByEmail(emailMinusculo).filter(Usuario::temSenha);
		// sem conta ou sem senha tambem gasta um hash: o tempo de resposta nao denuncia quem existe
		var confere = confere(senha, usuario.map(Usuario::getSenha).orElse(hashFicticio));
		if (!confere || usuario.isEmpty() || !usuario.get().isAtivo()) {
			throw new ErroDeNegocio(HttpStatus.UNAUTHORIZED, "credenciais_invalidas", MENSAGEM, MENSAGEM);
		}
		var u = usuario.get();
		if (senhas.upgradeEncoding(u.getSenha())) u.definirSenha(senhas.encode(senha));
		u.registrarLogin(Instant.now());
		return u;
	}

	private boolean confere(String senha, String hash) {
		try {
			return senhas.matches(senha, hash);
		} catch (IllegalArgumentException hashIlegivel) { // hash importado sem prefixo {id}: nao e senha utilizavel
			return false;
		}
	}
}
