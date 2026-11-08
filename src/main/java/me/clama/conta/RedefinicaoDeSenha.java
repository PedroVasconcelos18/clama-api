package me.clama.conta;

import java.time.Duration;
import java.util.Locale;
import me.clama.conta.senha.ValidadorDeSenha;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** "Esqueci a senha" e o uso do link (redefinir ou definir): hash fora de transacao, escrita curta com {@code travar}. */
@Service
public class RedefinicaoDeSenha {

	private static final Logger LOG = LoggerFactory.getLogger(RedefinicaoDeSenha.class);
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

	RedefinicaoDeSenha(UsuarioRepository usuarios, TokensDeSenha tokens, EmailsDaConta emails, LimiteDeTaxa limite,
			ValidadorDeSenha validador, PasswordEncoder senhas, VagasDeHash vagas, SessoesDoUsuario sessoes,
			TransactionTemplate transacao) {
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

	/** Nunca revela se a conta existe: quem nao tem conta ativa gasta o mesmo trabalho (token + SHA-256), sem gravar. */
	public void pedir(String email, String ip) {
		var minusculo = email.strip().toLowerCase(Locale.ROOT);
		limite.exigir(new Cota("esqueci:email:" + minusculo, 3, Duration.ofHours(1)),
				new Cota("esqueci:ip:" + ip, 20, Duration.ofHours(1)));

		var usuario = usuarios.findByEmail(minusculo).or(() -> usuarios.findByEmailCanonico(EmailCanonico.de(minusculo)))
				.filter(Usuario::isAtivo);
		if (usuario.isEmpty()) {
			TokensDeSenha.hash(TokensDeSenha.novoToken()); // mesmo custo do caminho real
			return;
		}
		var token = tokens.emitir(usuario.get().getId(), TokensDeSenha.Finalidade.REDEFINIR);
		try { // sincrono, fora de transacao; falha nao muda a resposta
			emails.enviarRedefinicao(usuario.get(), token);
		} catch (RuntimeException e) {
			LOG.warn("Falha ao enviar o link de redefinir senha da conta {}", usuario.get().getId(), e);
		}
	}

	public void redefinir(String token, String novaSenha) {
		var id = tokens.espiar(token).orElseThrow(RedefinicaoDeSenha::linkInvalido);
		var usuario = usuarios.findById(id).filter(Usuario::isAtivo).orElseThrow(RedefinicaoDeSenha::linkInvalido);
		var fraquezas = validador.validar(novaSenha, usuario.getEmail()); // antes de gastar o token: pode tentar de novo
		if (!fraquezas.isEmpty()) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "senha_fraca", "A nova senha é fraca.",
					"Escolha uma senha mais forte.", fraquezas.stream().map(m -> new ErroDeCampo("novaSenha", m)).toList());
		}
		var novoHash = vagas.comVaga(() -> senhas.encode(novaSenha));

		transacao.executeWithoutResult(s -> {
			var dono = tokens.consumir(token).orElseThrow(RedefinicaoDeSenha::linkInvalido); // atomico: so um ganha
			var travado = usuarios.travar(dono).filter(Usuario::isAtivo).orElseThrow(RedefinicaoDeSenha::linkInvalido);
			travado.definirSenha(novoHash);
		});
		sessoes.derrubarTodas(id); // depois do commit
	}

	private static ErroDeNegocio linkInvalido() {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "link_invalido", LINK_INVALIDO, LINK_INVALIDO);
	}
}
