package me.clama.conta.interno;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Criar conta" sem senha: a senha vem pelo link. Nunca revela se a conta existe: depois do limite responde na hora, e a
 * busca, a criacao e o envio rodam no executor. E-mail novo ganha conta e link de definir; conta ativa ja existente
 * recebe o mesmo link do "esqueci a senha"; conta inativa, nada.
 */
@Service
public class Cadastro {

	public static final String PASTORAL = "Se este e-mail puder ser usado, você vai receber em instantes um link para criar sua senha. Confira também o spam.";

	private final UsuarioRepository usuarios;
	private final TokensDeSenha tokens;
	private final EmailsDaConta emails;
	private final RedefinicaoDeSenha redefinicao;
	private final LimiteDeTaxa limite;
	private final EnvioAssincrono envio;
	private final TransactionTemplate transacao;
	private final Clock relogio;

	Cadastro(UsuarioRepository usuarios, TokensDeSenha tokens, EmailsDaConta emails, RedefinicaoDeSenha redefinicao,
			LimiteDeTaxa limite, EnvioAssincrono envio, TransactionTemplate transacao, Clock relogio) {
		this.usuarios = usuarios;
		this.tokens = tokens;
		this.emails = emails;
		this.redefinicao = redefinicao;
		this.limite = limite;
		this.envio = envio;
		this.transacao = transacao;
		this.relogio = relogio;
	}

	public void cadastrar(String nome, String email, String ip) {
		var minusculo = email.strip().toLowerCase(Locale.ROOT);
		limite.exigir(new Cota("cadastro:email:" + EmailCanonico.de(minusculo), 3, Duration.ofHours(1)),
				new Cota("cadastro:ip:" + ip, 20, Duration.ofHours(1)));
		var limpo = nome.strip();
		envio.executar("cadastro", () -> criarOuAvisar(limpo, minusculo));
	}

	private void criarOuAvisar(String nome, String minusculo) {
		var canonico = EmailCanonico.de(minusculo);
		var criou = transacao.execute(s -> usuarios.inserirSeAusente(minusculo, canonico, nome, relogio.instant())) == 1;
		if (!criou) {
			redefinicao.enviarLink(minusculo);
			return;
		}
		var usuario = usuarios.findByEmailCanonico(canonico).orElseThrow();
		emails.enviarDefinicao(usuario, tokens.emitir(usuario.getId(), TokensDeSenha.Finalidade.DEFINIR));
	}
}
