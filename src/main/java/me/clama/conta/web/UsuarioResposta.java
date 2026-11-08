package me.clama.conta.web;

import me.clama.conta.Papel;
import me.clama.conta.Usuario;

record UsuarioResposta(Long id, String email, String nomeCompleto, String nomeFormatoBlog, Papel papel) {

	static UsuarioResposta de(Usuario u) {
		return new UsuarioResposta(u.getId(), u.getEmail(), u.getNomeCompleto(), u.getNomeFormatoBlog(), u.getPapel());
	}
}
