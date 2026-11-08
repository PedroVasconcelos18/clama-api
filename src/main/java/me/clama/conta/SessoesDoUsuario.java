package me.clama.conta;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Derruba sessoes pelo indice PRINCIPAL_NAME (= id do usuario): troca de senha, desativacao, rebaixamento. */
@Component
public class SessoesDoUsuario {

	private final FindByIndexNameSessionRepository<? extends Session> sessoes;

	SessoesDoUsuario(FindByIndexNameSessionRepository<? extends Session> sessoes) {
		this.sessoes = sessoes;
	}

	public void derrubarTodas(Long usuarioId) {
		derrubarOutras(usuarioId, null);
	}

	public void derrubarOutras(Long usuarioId, String sessaoAtualId) {
		sessoes.findByPrincipalName(usuarioId.toString()).keySet().stream()
				.filter(id -> !id.equals(sessaoAtualId))
				.forEach(sessoes::deleteById);
	}
}
