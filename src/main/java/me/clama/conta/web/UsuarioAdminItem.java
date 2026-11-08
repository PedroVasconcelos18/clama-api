package me.clama.conta.web;

import java.time.Instant;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.Usuario;

/** Linha da lista e detalhe do admin. Nunca a senha; contagens (pedidos, comentarios) entram nas ondas 4 e 6. */
record UsuarioAdminItem(Long id, String email, String nomeCompleto, Papel papel, boolean ativo, Instant criadoEm,
		Instant ultimoLoginEm, boolean temSenha) {

	static UsuarioAdminItem de(Usuario u) {
		return new UsuarioAdminItem(u.getId(), u.getEmail(), u.getNomeCompleto(), u.getPapel(), u.isAtivo(),
				u.getCriadoEm(), u.getUltimoLoginEm(), u.temSenha());
	}
}
