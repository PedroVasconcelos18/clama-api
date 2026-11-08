package me.clama.api.identidade.comando;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.comando.Comandos;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** create_clama_admin: sem cenario do Django (correcao deliberada, D7 e D15), so testado aqui. */
@SpringBootTest
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class CreateClamaAdminTest {
    static final String SENHA = "Rocha-firme-2026";

    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired SenhaDjango senhas;
    @Autowired ApplicationContext ctx;

    long autor;

    @BeforeEach
    @AfterEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE users_user, django_admin_log CASCADE");
    }

    @BeforeEach
    void autor() {
        jdbc.update("insert into django_content_type (app_label, model) values ('users', 'user') on conflict do nothing");
        autor = usuario("autor@exemplo.com");
    }

    long usuario(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("x");
        return usuarios.saveAndFlush(u).getId();
    }

    record Saida(String out, String err, int exit) {}

    Saida rodar(String stdin, String... args) {
        String[] todos = new String[args.length + 1];
        todos[0] = "create_clama_admin";
        System.arraycopy(args, 0, todos, 1, args.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Comandos.executar(todos, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader(stdin)),
                Path.of("").toAbsolutePath(), ctx);
        return new Saida(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), exit);
    }

    int total() {
        return jdbc.queryForObject("select count(*) from users_user", Integer.class);
    }

    int auditorias() {
        return jdbc.queryForObject("select count(*) from django_admin_log", Integer.class);
    }

    Map<String, Object> linha(String email) {
        return jdbc.queryForMap("select * from users_user where email = ?", email);
    }

    @Test
    void criaAdminComSenhaNaLinhaDeComando() {
        Saida s = rodar("", "--email", "  Novo@Exemplo.com ", "--password", SENHA, "--nome", "Novo Admin");
        assertThat(s.exit()).as(s.err()).isZero();
        assertThat(s.out()).isEqualTo("Admin criado com sucesso: novo@exemplo.com\n");
        Map<String, Object> u = linha("novo@exemplo.com");
        assertThat(u).containsEntry("is_staff", true).containsEntry("is_clama_admin", true)
                .containsEntry("is_superuser", false).containsEntry("is_active", true)
                .containsEntry("nome_completo", "Novo Admin");
        assertThat((String) u.get("password")).startsWith("argon2$");
        assertThat(senhas.matches(SENHA, (String) u.get("password"))).isTrue();
        assertThat(u.get("email_hash")).isNotEqualTo("");
    }

    @Test
    void senhaPeloPromptDoStdinSemTerminal() {
        Saida s = rodar(SENHA + "\n", "--email", "novo@exemplo.com");
        assertThat(s.exit()).as(s.err()).isZero();
        assertThat(s.err()).contains("Sem terminal").doesNotContain(SENHA);
        assertThat(senhas.matches(SENHA, (String) linha("novo@exemplo.com").get("password"))).isTrue();
    }

    @Test
    void semSenhaNemStdinNaoCria() {
        Saida s = rodar("", "--email", "novo@exemplo.com");
        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).contains("CommandError: Senha não informada");
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void senhaFracaERecusadaComOsTextosDoValidador() {
        Saida curta = rodar("", "--email", "novo@exemplo.com", "--password", "abc");
        assertThat(curta.exit()).isEqualTo(1);
        assertThat(curta.err()).contains("CommandError: ").contains("Esta senha é muito curta");
        Saida numerica = rodar("12345678\n", "--email", "novo@exemplo.com");
        assertThat(numerica.exit()).isEqualTo(1);
        assertThat(numerica.err()).contains("Esta senha é inteiramente numérica.");
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void superusuarioGravaIsSuperuserEIsClamaAdmin() {
        Saida s = rodar("", "--email", "resgate@exemplo.com", "--password", SENHA, "--superusuario");
        assertThat(s.exit()).as(s.err()).isZero();
        assertThat(linha("resgate@exemplo.com")).containsEntry("is_superuser", true).containsEntry("is_clama_admin", true)
                .containsEntry("is_staff", true);
    }

    @Test
    void duplicadoExatoERecusado() {
        usuario("ja@exemplo.com");
        Saida s = rodar("", "--email", "ja@exemplo.com", "--password", SENHA);
        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).startsWith("CommandError: Usuário com email ja@exemplo.com já existe.");
        assertThat(total()).isEqualTo(2);
    }

    @Test
    void duplicadoDiferindoSoNaCaixaERecusado() {
        usuario("Ja@Exemplo.com");
        Saida s = rodar("", "--email", "ja@exemplo.com", "--password", SENHA);
        assertThat(s.exit()).isEqualTo(1);
        assertThat(total()).isEqualTo(2);
    }

    /** O Django criava uma 2a conta: o Gmail e o mesmo endereco sem pontos, sem +tag e com googlemail. */
    @Test
    void criarSuperusuarioDuplicadoCanonico() {
        usuario("joao.silva@gmail.com");
        for (String email : List.of("joaosilva@gmail.com", "joao.silva+admin@gmail.com", "J.Oao.Silva@googlemail.com")) {
            Saida s = rodar("", "--email", email, "--password", SENHA, "--superusuario", "--autor", "" + autor);
            assertThat(s.exit()).as(email).isEqualTo(1);
            assertThat(s.err()).as(email).startsWith("CommandError: Usuário com email ");
        }
        assertThat(total()).isEqualTo(2); // so o autor e o existente
        assertThat(auditorias()).isZero();
    }

    @Test
    void comAutorGravaAuditoriaESemAutorNao() {
        rodar("", "--email", "sem@exemplo.com", "--password", SENHA);
        assertThat(auditorias()).isZero();
        Saida s = rodar("", "--email", "com@exemplo.com", "--password", SENHA, "--autor", "" + autor);
        assertThat(s.exit()).as(s.err()).isZero();
        assertThat(auditorias()).isOne();
        Map<String, Object> log = jdbc.queryForMap("select user_id, action_flag, object_id from django_admin_log");
        assertThat(log).containsEntry("user_id", autor).containsEntry("action_flag", 1)
                .containsEntry("object_id", String.valueOf(linha("com@exemplo.com").get("id")));
    }

    @Test
    void autorInexistenteOuInvalidoNaoCria() {
        assertThat(rodar("", "--email", "x@exemplo.com", "--password", SENHA, "--autor", "999999").exit()).isEqualTo(1);
        assertThat(rodar("", "--email", "x@exemplo.com", "--password", SENHA, "--autor", "abc").exit()).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void semEmailEErroDeUso() {
        assertThat(rodar("", "--password", SENHA).exit()).isEqualTo(2);
    }
}
