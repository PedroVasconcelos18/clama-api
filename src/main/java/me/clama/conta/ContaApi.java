package me.clama.conta;

import java.time.Clock;
import java.util.Locale;
import me.clama.conta.interno.ContaCriada;
import me.clama.conta.interno.EmailCanonico;
import me.clama.conta.interno.UsuarioRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Interface do modulo para os outros (doacao, oracao): achar ou criar a conta de quem doa. */
@Service
public class ContaApi {

	private final UsuarioRepository usuarios;
	private final ApplicationEventPublisher eventos;
	private final Clock relogio;

	ContaApi(UsuarioRepository usuarios, ApplicationEventPublisher eventos, Clock relogio) {
		this.usuarios = usuarios;
		this.eventos = eventos;
		this.relogio = relogio;
	}

	/**
	 * Conta existente pelo e-mail canonico: so devolve. Senao cria um CLIENTE sem senha e, depois do commit de quem
	 * chamou, manda o link de definir senha. Criacao simultanea do mesmo e-mail: o {@code ON CONFLICT DO NOTHING}
	 * espera o outro commit e solta sem erro (sem excecao, a transacao do chamador nao se perde), e a conta e relida.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UsuarioResumo provisionarOuVincular(String email, String nomeCompleto) {
		var canonico = EmailCanonico.de(email);
		var existente = usuarios.findByEmailCanonico(canonico);
		if (existente.isEmpty()) {
			var nome = nomeCompleto == null ? "" : nomeCompleto.strip();
			var criou = usuarios.inserirSeAusente(email.strip().toLowerCase(Locale.ROOT), canonico,
					nome.length() > 120 ? nome.substring(0, 120) : nome, relogio.instant()) == 1;
			existente = usuarios.findByEmailCanonico(canonico);
			if (criou) eventos.publishEvent(new ContaCriada(existente.orElseThrow().getId()));
		}
		var u = existente.orElseThrow();
		return new UsuarioResumo(u.getId(), u.getEmail(), u.getNomeCompleto());
	}
}
