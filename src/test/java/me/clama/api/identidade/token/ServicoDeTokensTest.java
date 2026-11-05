package me.clama.api.identidade.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.clama.api.BancoDeTeste;
import me.clama.api.comum.cripto.CriptoConfig;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BancoDeTeste.class, CriptoConfig.class, ServicoDeTokens.class, Jwt.class})
@ActiveProfiles("test")
class ServicoDeTokensTest {
    @Autowired ServicoDeTokens servico;
    @Autowired UsuarioRepository usuarios;
    @Autowired JdbcTemplate jdbc;

    private Usuario usuario() {
        Usuario u = new Usuario();
        u.setEmail(java.util.UUID.randomUUID() + "@x.com");
        u.setPassword("x");
        return usuarios.saveAndFlush(u);
    }

    @Test
    void emitePersisteOutstandingEAccessValida() {
        Usuario u = usuario();
        ParDeTokens p = servico.emitirPar(u);
        assertThat(servico.validarAcesso(p.access())).isEqualTo(u.getId());
        assertThatThrownBy(() -> servico.validarAcesso(p.refresh())).isInstanceOf(JwtInvalido.class);
        assertThat(jdbc.queryForObject("select count(*) from token_blacklist_outstandingtoken where user_id=? and token=?",
                Integer.class, u.getId(), p.refresh())).isEqualTo(1);
    }

    @Test
    void rotacaoRevogaAntigo() {
        ParDeTokens p1 = servico.emitirPar(usuario());
        ParDeTokens p2 = servico.rotacionar(p1.refresh());
        assertThat(p2.refresh()).isNotEqualTo(p1.refresh());
        assertThatThrownBy(() -> servico.rotacionar(p1.refresh())).isInstanceOf(JwtInvalido.class);
        assertThat(servico.rotacionar(p2.refresh()).refresh()).isNotNull();
    }

    @Test
    void usuarioInativoNaoRotaciona() {
        Usuario u = usuario();
        ParDeTokens p = servico.emitirPar(u);
        u.setActive(false);
        usuarios.saveAndFlush(u);
        assertThatThrownBy(() -> servico.rotacionar(p.refresh())).isInstanceOf(JwtInvalido.class);
    }

    @Test
    void revogarTodosDerrubaTodosOsRefresh() {
        Usuario u = usuario();
        ParDeTokens a = servico.emitirPar(u);
        ParDeTokens b = servico.emitirPar(u);
        servico.revogarTodos(u.getId());
        assertThatThrownBy(() -> servico.rotacionar(a.refresh())).isInstanceOf(JwtInvalido.class);
        assertThatThrownBy(() -> servico.rotacionar(b.refresh())).isInstanceOf(JwtInvalido.class);
    }

    @Test
    void formatoEmitido() {
        Usuario u = usuario();
        Jwt jwt = new Jwt("test-secret-key-do-not-use-in-production");
        for (String tk : new String[] {servico.emitirPar(u).access(), servico.emitirPar(u).refresh()}) {
            java.util.Map<String, Object> c = jwt.verificar(tk);
            assertThat(c.keySet()).containsExactlyInAnyOrder("token_type", "exp", "iat", "jti", "user_id");
            assertThat(c.get("user_id")).isEqualTo(String.valueOf(u.getId())).isInstanceOf(String.class);
            assertThat((String) c.get("jti")).matches("[0-9a-f]{32}");
        }
    }

    private String tokenCom(java.util.Map<String, Object> extra, String... remover) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(jwtDoTeste.verificar(servico.emitirPar(usuario()).refresh()));
        for (String r : remover) m.remove(r);
        m.putAll(extra);
        return jwtDoTeste.assinar(m);
    }

    @Autowired Jwt jwtDoTeste;

    @Test
    void userIdNumericoEhAceito() {
        Usuario u = usuario();
        String t = tokenCom(java.util.Map.of("user_id", u.getId()), "user_id");
        assertThat(servico.rotacionar(t).refresh()).isNotNull();
    }

    @Test
    void semJtiOuJtiNaoStringEhJwtInvalido() {
        for (String t : new String[] {tokenCom(java.util.Map.of(), "jti"), tokenCom(java.util.Map.of("jti", 5))}) {
            assertThatThrownBy(() -> servico.rotacionar(t)).isInstanceOf(JwtInvalido.class);
            assertThatThrownBy(() -> servico.revogar(t)).isInstanceOf(JwtInvalido.class);
        }
        String acc = tokenCom(java.util.Map.of("token_type", "access"), "jti");
        assertThatThrownBy(() -> servico.validarAcesso(acc)).isInstanceOf(JwtInvalido.class);
    }
}
