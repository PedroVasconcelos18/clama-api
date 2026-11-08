package me.clama.api.pedido.comando;

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
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * test_adotar_pedidos_orfaos.py (7) portado, mais o que o Java corrige (mapa §5.3): conta ambigua pulada, UPDATE so em
 * quem segue sem usuario, nenhum e-mail no log.
 */
@SpringBootTest
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class AdotarPedidosOrfaosTest {
    static final String PLANO = "55555555-5555-4555-8555-0000000000a9";

    @Autowired JdbcTemplate jdbc;
    @Autowired Fernet fernet;
    @Autowired UsuarioRepository usuarios;
    @Autowired ApplicationContext ctx;

    long dona;

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE orders_pedido, users_user CASCADE");
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade,
                    ordem, ativo, visivel) values (now(), now(), ?::uuid, 'P', 2000, 'd', 'simples', 1, true, true)
                on conflict (id) do nothing""", PLANO);
        dona = usuario("pedro@clama.me");
    }

    long usuario(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("x");
        return usuarios.saveAndFlush(u).getId();
    }

    UUID pedido(String email, Long user) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into orders_pedido (created_at, updated_at, id, nome, email, telefone, cpf_cnpj, sexo,
                    pedido_oracao, oracao_gerada, valor_centavos, canal_entrega, status, plano_id, consent_aceito,
                    eh_gratuito, retry_count, last_error, consent_versao, whatsapp_message_id, device_hash,
                    provider_payment_id, user_id)
                values (now(), '2026-01-01T00:00:00Z', ?, ?, ?, ?, ?, '', ?, '', 0, 'email', 'enviada', ?::uuid, true,
                    false, 0, '', '', '', '', '', ?)""", id, fernet.cifrar("Pedro Henrique"), fernet.cifrar(email),
                fernet.cifrar("+5511999998888"), fernet.cifrar("11144477735"), fernet.cifrar("Oração"), PLANO, user);
        return id;
    }

    Long dono(UUID pedido) {
        return jdbc.queryForObject("select user_id from orders_pedido where id = ?", Long.class, pedido);
    }

    record Saida(String out, String err, int exit) {}

    Saida rodar(String... args) {
        String[] todos = new String[args.length + 1];
        todos[0] = "adotar_pedidos_orfaos";
        System.arraycopy(args, 0, todos, 1, args.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Comandos.executar(todos, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("")),
                Path.of("").toAbsolutePath(), ctx);
        return new Saida(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), exit);
    }

    @Test
    void orfaoEVinculadoAContaDoEmail() {
        UUID p = pedido("pedro@clama.me", null);
        Saida s = rodar();
        assertThat(s.exit()).isZero();
        assertThat(dono(p)).isEqualTo(dona);
        assertThat(s.out()).contains("1 pedido(s) vinculado(s).");
    }

    @Test
    void caixaDoEmailNaoImpedeOVinculo() {
        UUID p = pedido("PEDRO@Clama.me", null);
        rodar();
        assertThat(dono(p)).isEqualTo(dona);
    }

    @Test
    void orfaoSemContaCorrespondenteContinuaOrfao() {
        UUID p = pedido("ninguem@exemplo.com", null);
        Saida s = rodar();
        assertThat(dono(p)).isNull();
        assertThat(s.out()).contains("  sem conta (ficam órfãos):  1");
    }

    @Test
    void pedidoJaVinculadoNaoETocado() {
        long outra = usuario("outra@clama.me");
        UUID p = pedido("pedro@clama.me", outra);
        Saida s = rodar();
        assertThat(dono(p)).isEqualTo(outra);
        assertThat(s.out()).isEqualTo("Pedidos órfãos com e-mail: 0\n");
    }

    @Test
    void dryRunNaoGrava() {
        UUID p = pedido("pedro@clama.me", null);
        Saida s = rodar("--dry-run");
        assertThat(dono(p)).isNull();
        assertThat(s.out()).endsWith("\n--dry-run: nada foi gravado.\n");
    }

    @Test
    void dryRunSeguidoDeExecucaoRealVincula() {
        UUID p = pedido("pedro@clama.me", null);
        rodar("--dry-run");
        rodar();
        assertThat(dono(p)).isEqualTo(dona);
    }

    @Test
    void rodarDuasVezesNaoMudaNada() {
        UUID p = pedido("pedro@clama.me", null);
        rodar();
        Saida s = rodar();
        assertThat(dono(p)).isEqualTo(dona);
        assertThat(s.out()).isEqualTo("Pedidos órfãos com e-mail: 0\n");
        assertThat(jdbc.queryForObject("select count(*) from orders_pedido where user_id is null", Integer.class))
                .isZero();
    }

    /** Dois usuarios no mesmo Gmail canonico: o Django adotava para o ultimo; aqui o pedido fica orfao e e contado. */
    @Test
    void contaAmbiguaFicaOrfaEEContada() {
        usuario("a.na@gmail.com");
        usuario("ana@gmail.com");
        UUID p = pedido("ana+doacao@gmail.com", null);
        Saida s = rodar();
        assertThat(dono(p)).isNull();
        assertThat(s.out()).contains("  com conta correspondente: 0", "  ambíguos (mais de uma conta com o e-mail, "
                + "ficam órfãos): 1", "0 pedido(s) vinculado(s).");
    }

    @Test
    void gmailCanonicoCasa() {
        long joao = usuario("joao@gmail.com");
        UUID p = pedido("j.oao+doacao@gmail.com", null);
        rodar();
        assertThat(dono(p)).isEqualTo(joao);
    }

    @Test
    void emailRestringeEIgnoraEspacosECaixa() {
        long outra = usuario("outra@clama.me");
        UUID meu = pedido("pedro@clama.me", null);
        UUID dela = pedido("outra@clama.me", null);
        rodar("--email", " PEDRO@clama.me ");
        assertThat(dono(meu)).isEqualTo(dona);
        assertThat(dono(dela)).isNull();
    }

    /** O provisionamento que chega no meio do caminho vence: o UPDATE so toca quem segue sem usuario. */
    @Test
    void naoSobrescreveVinculoQueChegouNoMeio() {
        UUID p = pedido("pedro@clama.me", null);
        long outra = usuario("outra@clama.me");
        // o comando ja leu o pedido como orfao; aqui o UPDATE condicional e exercido direto, como ele o faz
        jdbc.update("update orders_pedido set user_id = ? where id = ?", outra, p);
        int n = jdbc.update("update orders_pedido set user_id = ? where id = ? and user_id is null", dona, p);
        assertThat(n).isZero();
        assertThat(dono(p)).isEqualTo(outra);
    }

    @Test
    void pedidoEmErroAdotadoNaoMudaStatus() {
        UUID p = pedido("pedro@clama.me", null);
        jdbc.update("update orders_pedido set status = 'erro' where id = ?", p);
        rodar();
        assertThat(jdbc.queryForObject("select status from orders_pedido where id = ?", String.class, p))
                .isEqualTo("erro");
        assertThat(jdbc.queryForObject("select updated_at = '2026-01-01T00:00:00Z' from orders_pedido where id = ?",
                Boolean.class, p)).isTrue(); // paridade: save(update_fields=["user"]) nao mexe em updated_at
    }
}
