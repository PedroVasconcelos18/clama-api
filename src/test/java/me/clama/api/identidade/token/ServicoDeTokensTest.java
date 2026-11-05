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
        u.setEmail("t@x.com");
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
}
