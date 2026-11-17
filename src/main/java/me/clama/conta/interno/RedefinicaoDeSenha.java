package me.clama.conta.interno;

import java.time.Duration;
import java.util.Locale;
import me.clama.conta.interno.senha.ValidadorDeSenha;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.infra.email.EnvioAssincrono;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** "Esqueci a senha" e o uso do link (redefinir ou definir): hash fora de transacao, escrita curta com {@code travar}. */
@Service
public class RedefinicaoDeSenha {

	public static final String PASTORAL_ESQUECI = "Se este e-mail estiver cadastrado, você receberá um link para criar uma nova senha em instantes. Verifique também a caixa de spam.";
	public static final String PASTORAL_REDEFINIDA = "Senha criada — agora é só entrar.";
	static final String LINK_INVALIDO = "Este link não vale mais. Peça um novo.";

	private final UsuarioRepository usuarios;
	private final TokensDeSenha tokens;
	private final EmailsDaConta emails;
	private final LimiteDeTaxa limite;
	private final ValidadorDeSenha validador;
	private final PasswordEncoder senhas;
	private final VagasDeHash vagas;
	private final SessoesDoUsuario sessoes;
	private final TransactionTemplate transacao;
	private final EnvioAssincrono envio;

	RedefinicaoDeSenha(UsuarioRepository usuarios, TokensDeSenha tokens, EmailsDaConta emails, LimiteDeTaxa limite,
			ValidadorDeSenha validador, PasswordEncoder senhas, VagasDeHash vagas, SessoesDoUsuario sessoes,
			TransactionTemplate transacao, EnvioAssincrono envio) {
		this.envio = envio;
		this.usuarios = usuarios;
		this.tokens = tokens;
		this.emails = emails;
		this.limite = limite;
		this.validador = validador;
		this.senhas = senhas;
		this.vagas = vagas;
		this.sessoes = sessoes;
		this.transacao = transacao;
	}

	/**
	 * Nunca revela se a conta existe: depois do limite responde na hora; busca, token e envio rodam no executor, igual
	 * para quem tem conta e para quem nao tem (o tempo da resposta nao diz nada).
	 */
	public void pedir(String email, String ip) {
		var minusculo = email.strip().toLowerCase(Locale.ROOT);
		limite.exigir(new Cota("esqueci:email:" + EmailCanonico.de(minusculo), 3, Duration.ofHours(1)),
				new Cota("esqueci:ip:" + ip, 20, Duration.ofHours(1)));
		envio.executar("link de redefinir senha", () -> enviarLink(minusculo));
	}

	/** Tambem do {@link Cadastro} de e-mail que ja tem conta. Roda no executor: so o e-mail minusculo, sem limite. */
	void enviarLink(String minusculo) {
		var usuario = usuarios.findByEmail(minusculo).or(() -> usuarios.findByEmailCanonico(EmailCanonico.de(minusculo)))
				.filter(Usuario::isAtivo);
		if (usuario.isEmpty()) return;
		var token = tokens.emitir(usuario.get().getId(), TokensDeSenha.Finalidade.REDEFINIR);
		emails.enviarRedefinicao(usuario.get(), token); // falha: EnvioAssincrono registra no log e no Sentry
	}

	public void redefinir(String token, String novaSenha) {
		var id = tokens.espiar(token).orElseThrow(RedefinicaoDeSenha::linkInvalido);
		var usuario = usuarios.findById(id).filter(Usuario::isAtivo).orElseThrow(RedefinicaoDeSenha::linkInvalido);
		validador.exigirForte(novaSenha, usuario.getEmail(), "novaSenha"); // antes de gastar o token: pode tentar de novo
		var novoHash = vagas.comVaga(() -> senhas.encode(novaSenha));

		transacao.executeWithoutResult(s -> {
			var dono = tokens.consumir(token).orElseThrow(RedefinicaoDeSenha::linkInvalido); // atomico: so um ganha
			tokens.revogarTodos(dono); // os demais links pendentes (inclusive DEFINIR) morrem junto
			var travado = usuarios.travar(dono).filter(Usuario::isAtivo).orElseThrow(RedefinicaoDeSenha::linkInvalido);
			travado.definirSenha(novoHash);
		});
		sessoes.derrubarTodas(id); // depois do commit
	}

	private static ErroDeNegocio linkInvalido() {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "link_invalido", LINK_INVALIDO, LINK_INVALIDO);
	}
}
