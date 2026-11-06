package me.clama.api.comum.email;

import java.net.IDN;
import java.util.regex.Pattern;
import me.clama.api.comum.ratelimit.ClienteIp;

/**
 * django.core.validators.EmailValidator (Django 4.2), o do EmailField do DRF.
 * ponytail: [A-Z] com IGNORECASE no Python tambem casa "ſ" e "K" (Kelvin); aqui so ASCII.
 */
public final class ValidadorDeEmail {
    private static final Pattern USUARIO = Pattern.compile(
            "[-!#$%&'*+/=?^_`{}|~0-9A-Z]+(\\.[-!#$%&'*+/=?^_`{}|~0-9A-Z]+)*"
                    + "|\"([\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f!#-\\[\\]-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMINIO = Pattern.compile(
            "((?:[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?\\.)+)(?:[A-Z0-9-]{2,63}(?<!-))", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL = Pattern.compile("\\[([A-F0-9:.]+)\\]", Pattern.CASE_INSENSITIVE);

    private ValidadorDeEmail() {}

    public static boolean valido(String v) {
        if (v == null || v.isEmpty() || !v.contains("@") || v.codePointCount(0, v.length()) > 320) {
            return false;
        }
        int arroba = v.lastIndexOf('@');
        String usuario = v.substring(0, arroba);
        String dominio = v.substring(arroba + 1);
        if (!USUARIO.matcher(usuario).matches()) {
            return false;
        }
        if (dominio.equals("localhost") || dominioValido(dominio)) {
            return true;
        }
        try {
            return dominioValido(IDN.toASCII(dominio)); // punycode(): codec "idna" do Python (IDNA 2003)
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean dominioValido(String d) {
        if (DOMINIO.matcher(d).matches()) {
            return true;
        }
        var m = LITERAL.matcher(d);
        return m.matches() && ClienteIp.valido(m.group(1));
    }
}
