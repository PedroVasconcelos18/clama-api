package me.clama.api.identidade.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.api.comum.cripto.CriptoConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** HS256 igual ao simplejwt. Usa javax.crypto.Mac, que aceita chave de qualquer tamanho. */
@Component
public class Jwt {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] chave;

    public Jwt(@Value("${DJANGO_SECRET_KEY:}") String chave) {
        this.chave = CriptoConfig.exigir("DJANGO_SECRET_KEY", chave).getBytes(StandardCharsets.UTF_8);
    }

    public String assinar(Map<String, Object> claims) {
        String corpo = HEADER + "." + B64.encodeToString(JSON.writeValueAsBytes(claims));
        return corpo + "." + B64.encodeToString(hmac(corpo));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> verificar(String token) {
        try {
            String[] p = token.split("\\.", -1);
            if (p.length != 3) throw new JwtInvalido("formato");
            byte[] esperado = hmac(p[0] + "." + p[1]);
            if (!MessageDigest.isEqual(esperado, Base64.getUrlDecoder().decode(p[2]))) throw new JwtInvalido("assinatura");
            Map<String, Object> header = JSON.readValue(Base64.getUrlDecoder().decode(p[0]), Map.class);
            if (!"HS256".equals(header.get("alg"))) throw new JwtInvalido("alg");
            Map<String, Object> claims = JSON.readValue(Base64.getUrlDecoder().decode(p[1]), Map.class);
            if (!(claims.get("exp") instanceof Number exp) || exp.longValue() <= Instant.now().getEpochSecond()) {
                throw new JwtInvalido("expirado");
            }
            return claims;
        } catch (JwtInvalido e) {
            throw e;
        } catch (RuntimeException e) {
            throw new JwtInvalido("malformado");
        }
    }

    private byte[] hmac(String s) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(chave, "HmacSHA256"));
            return mac.doFinal(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
