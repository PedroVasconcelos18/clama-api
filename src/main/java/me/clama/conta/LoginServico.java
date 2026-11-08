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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confere e-mail e senha; a sessao fica com o controller. Toda falha e o mesmo 401 generico. O hash roda fora de transacao e
 * limitado por {@link VagasDeHash}: rajada de login nao esgota o heap nem o pool do banco.
 */
@Service
public class LoginServico {

	static final String MENSAGEM = "Email ou senha inválidos.";

	private final UsuarioRepository usuarios;
	private final PasswordEncoder senhas;
	private final LimiteDeTaxa limite;
	private final TransactionTemplate transacao;
	private final String hashFicticio;
	private final VagasDeHash vagas;

	LoginServico(UsuarioRepository usuarios, PasswordEncoder senhas, LimiteDeTaxa limite,
			TransactionTemplate transacao, VagasDeHash vagas) {
		this.vagas = vagas;
		this.usuarios = usuarios;
		this.senhas = senhas;
		this.limite = limite;
		this.transacao = transacao;
		this.hashFicticio = senhas.encode("hash-ficticio-para-igualar-o-tempo");
	}

	public Usuario autenticar(String email, String senha, String ip) {
		var emailMinusculo = email.strip().toLowerCase(Locale.ROOT);
		limite.exigir(new Cota("login:email:" + emailMinusculo, 5, Duration.ofMinutes(15)),
				new Cota("login:ip:" + ip, 30, Duration.ofMinutes(1)));

		var usuario = usuarios.findByEmail(emailMinusculo).filter(Usuario::temSenha); // leitura curta, sem transacao aberta
		// sem conta ou sem senha tambem gasta um hash: o tempo de resposta nao denuncia quem existe
		var hash = usuario.map(Usuario::getSenha).orElse(hashFicticio);
		var novoHash = vagas.comVaga(() -> {
			if (!confere(senha, hash) || usuario.isEmpty() || !usuario.get().isAtivo()) {
				throw new ErroDeNegocio(HttpStatus.UNAUTHORIZED, "credenciais_invalidas", MENSAGEM, MENSAGEM);
			}
			return senhas.upgradeEncoding(hash) ? senhas.encode(senha) : null;
		});

		var regravar = novoHash;
		return transacao.execute(s -> { // transacao curta: so a escrita, com o usuario relido
			var u = usuarios.findById(usuario.get().getId()).orElseThrow();
			u.registrarLogin(Instant.now());
			if (regravar != null) u.definirSenha(regravar);
			return u;
		});
	}

	private boolean confere(String senha, String hash) {
		try {
			return senhas.matches(senha, hash);
		} catch (IllegalArgumentException hashIlegivel) { // hash importado sem prefixo {id}: nao e senha utilizavel
			return false;
		}
	}
}
