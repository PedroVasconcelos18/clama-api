package me.clama.api.comum.cripto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Fernet compativel com o Python: 0x80 | ts(8) | IV(16) | AES-128-CBC | HMAC-SHA256(32). Sem TTL. */
public class Fernet {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec assinatura;
    private final SecretKeySpec cifra;

    public Fernet(String chaveBase64Url) {
        byte[] k = Base64.getUrlDecoder().decode(chaveBase64Url);
        if (k.length != 32) {
            throw new IllegalArgumentException("Chave Fernet deve ter 32 bytes");
        }
        assinatura = new SecretKeySpec(Arrays.copyOfRange(k, 0, 16), "HmacSHA256");
        cifra = new SecretKeySpec(Arrays.copyOfRange(k, 16, 32), "AES");
    }

    public String cifrar(String claro) {
        try {
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, cifra, new IvParameterSpec(iv));
            byte[] ct = c.doFinal(claro.getBytes(StandardCharsets.UTF_8));
            byte[] corpo = ByteBuffer.allocate(1 + 8 + 16 + ct.length)
                    .put((byte) 0x80).putLong(Instant.now().getEpochSecond()).put(iv).put(ct).array();
            byte[] tok = Arrays.copyOf(corpo, corpo.length + 32);
            System.arraycopy(hmac(corpo, corpo.length), 0, tok, corpo.length, 32);
            return Base64.getUrlEncoder().encodeToString(tok);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decifrar(String token) throws TokenInvalido {
        byte[] t;
        try {
            t = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new TokenInvalido("base64 invalido");
        }
        if (t.length < 1 + 8 + 16 + 16 + 32 || t[0] != (byte) 0x80) {
            throw new TokenInvalido("formato invalido");
        }
        int fim = t.length - 32;
        try {
            if (!MessageDigest.isEqual(hmac(t, fim), Arrays.copyOfRange(t, fim, t.length))) {
                throw new TokenInvalido("assinatura invalida");
            }
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, cifra, new IvParameterSpec(t, 9, 16));
            return new String(c.doFinal(t, 25, fim - 25), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new TokenInvalido("payload invalido");
        }
    }

    private byte[] hmac(byte[] dados, int len) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(assinatura);
        m.update(dados, 0, len);
        return m.doFinal();
    }
}
