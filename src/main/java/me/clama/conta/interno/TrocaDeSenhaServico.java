package me.clama.conta.interno;

import java.time.Duration;
import me.clama.conta.interno.senha.ValidadorDeSenha;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Troca de senha sem segurar lock durante o hash: confere a atual e calcula o novo hash fora de transacao, e so entao
 * trava a linha, confirma que o hash gravado e o mesmo que foi conferido e grava. Duas trocas simultaneas: so uma passa.
 */
@Service
public class TrocaDeSenhaServico {

	public static final String PASTORAL = "Senha atualizada — você já pode seguir em paz.";

	private final UsuarioRepository usuarios;
	private final PasswordEncoder senhas;
	private final ValidadorDeSenha validador;
	private final LimiteDeTaxa limite;
	private final VagasDeHash vagas;
	private final SessoesDoUsuario sessoes;
	private final TransactionTemplate transacao;
	private final TokensDeSenha tokens;

	TrocaDeSenhaServico(UsuarioRepository usuarios, PasswordEncoder senhas, ValidadorDeSenha validador,
			LimiteDeTaxa limite, VagasDeHash vagas, SessoesDoUsuario sessoes, TransactionTemplate transacao,
			TokensDeSenha tokens) {
		this.tokens = tokens;
		this.usuarios = usuarios;
		this.senhas = senhas;
		this.validador = validador;
		this.limite = limite;
		this.vagas = vagas;
		this.sessoes = sessoes;
		this.transacao = transacao;
	}

	/** Falha com ErroDeNegocio; a sessao atual ({@code sessaoAtualId}) continua valida, as outras caem. */
	public void trocar(Long id, String senhaAtual, String novaSenha, String sessaoAtualId) {
		limite.exigir(new Cota("conta:senha:" + id, 10, Duration.ofHours(1)));
		var usuario = usuarios.findById(id).filter(Usuario::isAtivo)
				.orElseThrow(() -> new ErroDeNegocio(HttpStatus.UNAUTHORIZED, "nao_autenticado", "Não autenticado.", null));
		var hashConferido = usuario.getSenha();

		var atualCorreta = usuario.temSenha() && vagas.comVaga(() -> {
			try {
				return senhas.matches(senhaAtual, hashConferido);
			} catch (IllegalArgumentException hashIlegivel) {
				return false;
			}
		});
		if (!atualCorreta) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "senha_atual_invalida", "A senha atual está incorreta.",
					"A senha atual está incorreta.");
		}
		validador.exigirForte(novaSenha, usuario.getEmail(), "novaSenha");
		var novoHash = vagas.comVaga(() -> senhas.encode(novaSenha));

		transacao.executeWithoutResult(s -> {
			var travado = usuarios.travar(id).orElseThrow();
			if (!hashConferido.equals(travado.getSenha())) {
				throw new ErroDeNegocio(HttpStatus.CONFLICT, "senha_alterada", "Sua senha acabou de mudar. Tente de novo.",
						"Sua senha acabou de mudar. Tente de novo.");
			}
			travado.definirSenha(novoHash);
			tokens.revogarTodos(id); // link pendente de antes da troca nao vale mais
		});
		sessoes.derrubarOutras(id, sessaoAtualId); // depois do commit: so cai a sessao se a senha de fato mudou
	}
}
