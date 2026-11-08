package me.clama.api.blog.comando;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.comando.Comandos;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** test_management_purgar_lgpd.py (8) portado, mais o prompt, o dry-run e as duas contas que so diferem na caixa. */
@SpringBootTest
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class PurgarDadosBlogCustomerTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ApplicationContext ctx;

    UUID espelho;

    @AfterEach
    @BeforeEach
    void truncar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE orders_pedido, blog_postespelho, users_user CASCADE");
        espelho = UUID.randomUUID();
        jdbc.update("""
                insert into blog_postespelho (id, created_at, updated_at, wp_post_id, slug, titulo, status, url)
                values (?, now(), now(), 1, 'p', 'P', 'publish', 'https://clama.me/blog/p')""", espelho);
    }

    long usuario(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("x");
        return usuarios.saveAndFlush(u).getId();
    }

    void comentario(long user) {
        jdbc.update("""
                insert into blog_comentario (id, created_at, updated_at, customer_id, post_espelho_id, conteudo,
                    ip_address, is_suspeito) values (gen_random_uuid(), now(), now(), ?, ?, 'Amém', '', false)""", user,
                espelho);
    }

    void reacao(long user) {
        jdbc.update("""
                insert into blog_reacao (id, created_at, updated_at, tipo, customer_id, post_espelho_id)
                values (gen_random_uuid(), now(), now(), 'like', ?, ?)""", user, espelho);
    }

    int contar(String tabela, long user) {
        return jdbc.queryForObject("select count(*) from " + tabela + " where customer_id = ?", Integer.class, user);
    }

    record Saida(String out, String err, int exit) {}

    Saida rodar(String stdin, String... args) {
        String[] todos = new String[args.length + 1];
        todos[0] = "purgar_dados_blog_customer";
        System.arraycopy(args, 0, todos, 1, args.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Comandos.executar(todos, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader(stdin)),
                Path.of("").toAbsolutePath(), ctx);
        return new Saida(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), exit);
    }

    @Test
    void apagaComentariosEReacoes() {
        long c = usuario("juliana@clama.test");
        comentario(c);
        comentario(c);
        reacao(c);
        long outro = usuario("outro@clama.test");
        comentario(outro);
        Saida s = rodar("", "juliana@clama.test", "--yes");
        assertThat(s.exit()).isZero();
        assertThat(contar("blog_comentario", c)).isZero();
        assertThat(contar("blog_reacao", c)).isZero();
        assertThat(contar("blog_comentario", outro)).isOne();
        assertThat(s.out()).contains("Removidos: 2 comentarios, 1 reacoes");
    }

    @Test
    void dryRunNaoApaga() {
        long c = usuario("juliana@clama.test");
        comentario(c);
        reacao(c);
        Saida s = rodar("", "juliana@clama.test", "--dry-run");
        assertThat(contar("blog_comentario", c)).isOne();
        assertThat(contar("blog_reacao", c)).isOne();
        assertThat(s.out()).contains("DRY-RUN", "1 comentarios", "1 reacoes");
    }

    @Test
    void dryRunVenceYes() {
        long c = usuario("juliana@clama.test");
        comentario(c);
        rodar("", "juliana@clama.test", "--dry-run", "--yes");
        assertThat(contar("blog_comentario", c)).isOne();
    }

    @Test
    void contaNaoEDeletada() {
        long c = usuario("juliana@clama.test");
        comentario(c);
        rodar("", "juliana@clama.test", "--yes");
        assertThat(jdbc.queryForObject("select count(*) from users_user where id = ?", Integer.class, c)).isOne();
    }

    @Test
    void pedidoNaoEDeletado() {
        long c = usuario("juliana@clama.test");
        comentario(c);
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade,
                    ordem, ativo, visivel) values (now(), now(), '55555555-5555-4555-8555-0000000000aa', 'P', 2000, 'd',
                    'simples', 1, true, true) on conflict (id) do nothing""");
        jdbc.update("""
                insert into orders_pedido (created_at, updated_at, id, nome, email, telefone, cpf_cnpj, sexo,
                    pedido_oracao, oracao_gerada, valor_centavos, canal_entrega, status, plano_id, consent_aceito,
                    eh_gratuito, retry_count, last_error, consent_versao, whatsapp_message_id, device_hash,
                    provider_payment_id, user_id)
                values (now(), now(), gen_random_uuid(), 'n', 'e', 't', 'c', '', 'p', '', 0, 'email', 'enviada',
                    '55555555-5555-4555-8555-0000000000aa', true, false, 0, '', '', '', '', '', ?)""", c);
        rodar("", "juliana@clama.test", "--yes");
        assertThat(jdbc.queryForObject("select count(*) from orders_pedido where user_id = ?", Integer.class, c))
                .isOne();
    }

    @Test
    void banimentoNaoEDeletado() {
        long c = usuario("juliana@clama.test");
        long admin = usuario("admin@clama.test");
        comentario(c);
        jdbc.update("""
                insert into blog_customerbanido (created_at, updated_at, id, motivo, banido_em, banido_por_id,
                    customer_id) values (now(), now(), gen_random_uuid(), 'Ofensa', now(), ?, ?)""", admin, c);
        rodar("", "juliana@clama.test", "--yes");
        assertThat(contar("blog_customerbanido", c)).isOne();
    }

    @Test
    void customerInexistenteDaErro() {
        Saida s = rodar("", "naoexiste@clama.test", "--yes");
        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).startsWith("CommandError: Customer com email 'naoexiste@clama.test' nao encontrado.");
    }

    @Test
    void buscaSemDiferenciarCaixa() {
        long c = usuario("JuLiAnA@clama.test");
        comentario(c);
        Saida s = rodar("", "juliana@clama.test", "--yes");
        assertThat(s.out()).contains("Removidos: 1 comentarios");
    }

    @Test
    void customerSemDadosNoBlog() {
        usuario("quieto@clama.test");
        Saida s = rodar("", "quieto@clama.test", "--yes");
        assertThat(s.out()).contains("Removidos: 0 comentarios, 0 reacoes");
    }

    @Test
    void promptConfirmaComYMaiusculoECancelaComOutraResposta() {
        long c = usuario("beto@clama.test");
        comentario(c);
        Saida cancelado = rodar("yes\n", "beto@clama.test");
        assertThat(cancelado.exit()).isZero();
        assertThat(cancelado.out()).endsWith("Confirma? [y/N]: Cancelado.\n").startsWith("Voce esta prestes a apagar");
        assertThat(contar("blog_comentario", c)).isOne();
        Saida confirmado = rodar("Y\n", "beto@clama.test");
        assertThat(confirmado.out()).endsWith("Confirma? [y/N]: Removidos: 1 comentarios, 0 reacoes de beto@clama.test "
                + "(user_id=" + c + ")\n");
        assertThat(contar("blog_comentario", c)).isZero();
    }

    @Test
    void promptSemRespostaNoStdinNaoApagaNada() {
        long c = usuario("beto@clama.test");
        comentario(c);
        Saida s = rodar("", "beto@clama.test");
        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).contains("CommandError: Sem resposta no stdin");
        assertThat(contar("blog_comentario", c)).isOne();
    }

    /** O Django morria com MultipleObjectsReturned (traceback); aqui e erro limpo e nada e apagado. */
    @Test
    void duasContasSoNaCaixaDaoErroLimpo() {
        long a = usuario("ana@exemplo.com");
        long b = usuario("ANA@exemplo.com");
        comentario(a);
        comentario(b);
        Saida s = rodar("", "ana@exemplo.com", "--yes");
        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).startsWith("CommandError: Mais de uma conta").doesNotContain("Traceback", "Exception");
        assertThat(contar("blog_comentario", a) + contar("blog_comentario", b)).isEqualTo(2);
    }
}
