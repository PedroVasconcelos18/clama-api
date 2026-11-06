package me.clama.api.conta;

import java.security.SecureRandom;

/**
 * gerar_senha_temporaria (freemium/temp_password.py): 14 caracteres de um alfabeto sem ambiguidade (sem 0/O/o, I/l/1),
 * cerca de 80 bits.
 */
public final class GeradorDeSenhaTemporaria {
    static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ" + "abcdefghijkmnpqrstuvwxyz" + "23456789";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private GeradorDeSenhaTemporaria() {}

    public static String gerar() {
        StringBuilder s = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            s.append(ALFABETO.charAt(ALEATORIO.nextInt(ALFABETO.length())));
        }
        return s.toString();
    }
}
