package me.clama.api.identidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.cripto.CriptoConfig;
import me.clama.api.comum.cripto.HashIdentificador;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BancoDeTeste.class, CriptoConfig.class})
@ActiveProfiles("test")
class UsuarioRepositoryTest {
    // Segredo do perfil de teste (!= segredo do fixture), por isso nao usamos a secao hmac.
    private final HashIdentificador hash = new HashIdentificador("dev-only-do-not-use-in-prod");

    @Autowired UsuarioRepository repo;
    @Autowired JdbcTemplate jdbc;

    private Usuario novo(String email, String cpf) {
        Usuario u = new Usuario();
        u.setPassword("x");
        u.setEmail(email);
        u.setCpfCnpj(cpf);
        return u;
    }

    @Test
    void salvarCalculaHashes() {
        Usuario u = repo.saveAndFlush(novo("Ana@X.com", "123.456.789-09"));
        assertThat(u.getCpfHash()).isEqualTo(hash.hashCpfCnpj("123.456.789-09"));
        assertThat(u.getEmailHash()).isEqualTo(hash.hashEmail("Ana@X.com"));
        assertThat(u.getTelefoneHash()).isEqualTo(hash.hashTelefone("")).hasSize(64);
    }

    @Test
    void leCpfCifradoPeloPython() {
        String cifrado = "gAAAAABq7M3WS64HLGVd8vErbEFjgxa29BOSTZBbg3ceBcRzI0tCQU7nrtskpXrMj6OMSPPKHP7P9UAAT8Hs25G_QJX2R7Gm9w==";
        jdbc.update("""
                INSERT INTO users_user (password,is_superuser,email,is_staff,is_active,date_joined,name,is_clama_admin,
                  nome_completo,cpf_cnpj,force_change_password,email_hash,nome_format_blog,sexo)
                VALUES ('x',false,'py@x.com',false,true,now(),'',false,'',?,false,'h','','')""", cifrado);
        assertThat(repo.buscarPorEmailIgnorandoCaixa("py@x.com").get(0).getCpfCnpj()).isEqualTo("123.456.789-09");
    }

    @Test
    void colunaGravadaEhFernet() {
        Usuario u = repo.saveAndFlush(novo("f@x.com", "123.456.789-09"));
        String col = jdbc.queryForObject("SELECT cpf_cnpj FROM users_user WHERE id=?", String.class, u.getId());
        assertThat(col).startsWith("gAAAAA");
    }

    @Test
    void buscaPorEmailIgnoraCaixa() {
        repo.saveAndFlush(novo("Ana@X.com", null));
        List<Usuario> r = repo.buscarPorEmailIgnorandoCaixa("ana@x.com");
        assertThat(r).hasSize(1);
    }

    @Test
    void novoUsuarioTemDefaultsDoDjango() {
        Usuario u = new Usuario();
        u.setEmail("d@x.com");
        repo.saveAndFlush(u);
        jdbc.queryForMap("SELECT * FROM users_user WHERE id=?", u.getId()).forEach((k, v) -> {
            switch (k) {
                case "nome_format_blog" -> assertThat(v).isEqualTo("compacto");
                case "date_joined" -> assertThat(v).isNotNull();
                case "is_active" -> assertThat(v).isEqualTo(true);
                case "is_staff", "is_superuser", "is_clama_admin", "force_change_password" -> assertThat(v).as(k).isEqualTo(false);
                case "name", "nome_completo", "sexo", "password" -> assertThat(v).as(k).isEqualTo("");
                case "last_login", "idade", "freemium_used_at", "cpf_cnpj", "telefone" -> assertThat(v).as(k).isNull();
                default -> { }
            }
        });
    }

    @Test
    void atualizarSoCpfRecalculaHashes() {
        Usuario u = repo.saveAndFlush(novo("a@x.com", "123.456.789-09"));
        String emailHash = u.getEmailHash();
        u.setCpfCnpj("987.654.321-00");
        repo.flush();
        String col = jdbc.queryForObject("SELECT cpf_hash FROM users_user WHERE id=?", String.class, u.getId());
        assertThat(col).isEqualTo(hash.hashCpfCnpj("987.654.321-00"));
        assertThat(u.getEmailHash()).isEqualTo(emailHash).isEqualTo(hash.hashEmail("a@x.com"));
    }

    @Test
    void cpfNuloGeraHashDeVazio() {
        Usuario u = repo.saveAndFlush(novo("n@x.com", null));
        assertThat(u.getCpfHash()).isEqualTo(hash.hashCpfCnpj(""));
    }
}
