package me.clama.api.identidade.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Formato de token do django.middleware.csrf (4.2): segredo de 32 caracteres [a-zA-Z0-9] no cookie; no header vale o
 * segredo puro ou mascarado (64). Gerar no formato do Django faz o rollback aceitar os cookies que o Java gravou.
 */
public final class CsrfDjango {
    // string.ascii_letters + string.digits, nessa ordem (a mascara depende do indice)
    static final String CARACTERES = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final int SEGREDO = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CsrfDjango() {
    }

    /** _get_new_csrf_string. */
    public static String novo() {
        StringBuilder sb = new StringBuilder(SEGREDO);
        for (int i = 0; i < SEGREDO; i++) {
            sb.append(CARACTERES.charAt(RANDOM.nextInt(CARACTERES.length())));
        }
        return sb.toString();
    }

    /** _get_secret: o segredo do cookie (desmascara o formato de 64 do Django &lt; 4.0); null se o formato nao vale. */
    public static String segredo(String cookie) {
        if (!formatoValido(cookie)) {
            return null;
        }
        return cookie.length() == SEGREDO ? cookie : desmascarar(cookie);
    }

    /** _does_token_match, com o _check_token_format do token enviado antes. */
    static boolean confere(String enviado, String segredo) {
        if (segredo == null || !formatoValido(enviado)) {
            return false;
        }
        String s = enviado.length() == SEGREDO ? enviado : desmascarar(enviado);
        return MessageDigest.isEqual(s.getBytes(StandardCharsets.US_ASCII), segredo.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean formatoValido(String token) {
        return token != null && (token.length() == SEGREDO || token.length() == 2 * SEGREDO)
                && token.chars().allMatch(c -> CARACTERES.indexOf(c) >= 0);
    }

    /** _unmask_cipher_token: chars[(token - mascara) mod 62]. */
    private static String desmascarar(String token) {
        StringBuilder sb = new StringBuilder(SEGREDO);
        for (int i = 0; i < SEGREDO; i++) {
            int x = CARACTERES.indexOf(token.charAt(SEGREDO + i)) - CARACTERES.indexOf(token.charAt(i));
            sb.append(CARACTERES.charAt(Math.floorMod(x, CARACTERES.length())));
        }
        return sb.toString();
    }
}
