package me.clama.api.identidade.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test
    void formatoEmitido() {
        Jwt jwt = new Jwt("chave-qualquer");
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("token_type", "access");
        m.put("exp", System.currentTimeMillis() / 1000 + 60);
        m.put("iat", System.currentTimeMillis() / 1000);
        m.put("jti", "ab");
        m.put("user_id", "7");
        Map<String, Object> volta = jwt.verificar(jwt.assinar(m));
        assertThat(volta.keySet()).isEqualTo(Set.of("token_type", "exp", "iat", "jti", "user_id"));
        assertThat(volta.get("user_id")).isInstanceOf(String.class);
    }

    @Test
    void lixoLancaJwtInvalido() {
        Jwt jwt = new Jwt("k");
        assertThatThrownBy(() -> jwt.verificar("a.b")).isInstanceOf(JwtInvalido.class);
        assertThatThrownBy(() -> jwt.verificar("a.b.c")).isInstanceOf(JwtInvalido.class);
    }
}
