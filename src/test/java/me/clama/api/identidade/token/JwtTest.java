package me.clama.api.identidade.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JwtTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> jwtFixture() throws Exception {
        try (var in = JwtTest.class.getResourceAsStream("/contrato/compat-cripto.json")) {
            return (Map<String, Object>) new ObjectMapper().readValue(in, Map.class).get("jwt");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void verificaTokensDoPython() throws Exception {
        Map<String, Object> f = jwtFixture();
        Jwt jwt = new Jwt((String) f.get("chave"));
        for (Map<String, Object> c : (List<Map<String, Object>>) f.get("casos")) {
            if ((Boolean) c.get("valido")) {
                Map<String, Object> claims = jwt.verificar((String) c.get("token"));
                assertThat(claims.get("user_id")).isEqualTo(c.get("user_id"));
                assertThat(claims.get("token_type")).isEqualTo(c.get("tipo"));
            } else {
                assertThatThrownBy(() -> jwt.verificar((String) c.get("token"))).isInstanceOf(JwtInvalido.class);
            }
        }
    }

    private static String b64(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String hs256(String chave, String corpo) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(chave.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(corpo.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static final String CORPO = b64("{\"exp\":4102444800,\"user_id\":\"1\"}");

    @Test
    void rejeitaAlgNone() {
        String t = b64("{\"alg\":\"none\"}") + "." + CORPO + ".";
        assertThatThrownBy(() -> new Jwt("k").verificar(t)).isInstanceOf(JwtInvalido.class);
    }

    @Test
    void rejeitaAlgDiferente() throws Exception {
        String pre = b64("{\"alg\":\"HS512\",\"typ\":\"JWT\"}") + "." + CORPO;
        String t = pre + "." + hs256("k", pre);
        assertThatThrownBy(() -> new Jwt("k").verificar(t)).isInstanceOf(JwtInvalido.class);
    }

    @Test
    void lixoLancaJwtInvalido() {
        Jwt jwt = new Jwt("k");
        assertThatThrownBy(() -> jwt.verificar("a.b")).isInstanceOf(JwtInvalido.class);
        assertThatThrownBy(() -> jwt.verificar("a.b.c")).isInstanceOf(JwtInvalido.class);
    }
}
