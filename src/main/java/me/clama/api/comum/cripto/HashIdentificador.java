package me.clama.api.comum.cripto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 dos identificadores, igual a clama/freemium/hashing.py. */
public class HashIdentificador {
    private final byte[] segredo;

    public HashIdentificador(String segredo) {
        this.segredo = segredo.getBytes(StandardCharsets.UTF_8);
    }

    public String hashEmail(String v) {
        return hash(normalizarEmail(v));
    }

    public String hashCpfCnpj(String v) {
        return hash(digitos(v));
    }

    public String hashTelefone(String v) {
        return hash(digitos(v));
    }

    public String hashIp(String v) {
        return hash(v == null ? "" : v.strip().toLowerCase());
    }

    public static String normalizarEmail(String email) {
        String c = email == null ? "" : email.strip().toLowerCase();
        int at = c.indexOf('@');
        if (at < 0) {
            return c;
        }
        String local = c.substring(0, at);
        String dominio = c.substring(at + 1);
        if (dominio.equals("gmail.com") || dominio.equals("googlemail.com")) {
            int plus = local.indexOf('+');
            if (plus >= 0) {
                local = local.substring(0, plus);
            }
            return local.replace(".", "") + "@gmail.com";
        }
        return c;
    }

    private static String digitos(String v) {
        return v == null ? "" : v.chars().filter(Character::isDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private String hash(String valor) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(segredo, "HmacSHA256"));
            return HexFormat.of().formatHex(m.doFinal(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
