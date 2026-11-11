package me.clama.freemium.web;

import java.time.Instant;
import me.clama.freemium.interno.AdminDeBloqueios.Linha;

/** Bloqueio no admin: o hash sai cortado (12 caracteres + "…"), nunca inteiro. */
record BloqueioAdmin(long id, Instant criadoEm, String emailHmacCurto, String cpfHmacCurto, boolean temIp) {

	static BloqueioAdmin de(Linha l) {
		return new BloqueioAdmin(l.id(), l.criadoEm(), curto(l.emailHmac()), curto(l.cpfHmac()), l.temIp());
	}

	private static String curto(String hash) {
		return hash.substring(0, Math.min(12, hash.length())) + "…";
	}
}
