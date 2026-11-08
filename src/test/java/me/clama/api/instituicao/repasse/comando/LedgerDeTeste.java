package me.clama.api.instituicao.repasse.comando;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.comando.Comandos;
import me.clama.api.comum.cripto.Fernet;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * As factories do Django (InstituicaoFactory, PedidoFactory, RepasseFactory) em SQL, com nome e e-mail cifrados como
 * no banco, e o comando rodado em processo como o jar (Comandos.executar).
 */
final class LedgerDeTeste {
    static final String PLANO = "55555555-5555-4555-8555-0000000000a8";

    private final JdbcTemplate jdbc;
    private final Fernet fernet;
    private final ApplicationContext ctx;
    private int sequencia;

    LedgerDeTeste(JdbcTemplate jdbc, Fernet fernet, ApplicationContext ctx) {
        this.jdbc = jdbc;
        this.fernet = fernet;
        this.ctx = ctx;
    }

    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE instituicoes_repasse, instituicoes_instituicao, orders_pedido, django_admin_log, "
                + "users_user CASCADE");
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade,
                    ordem, ativo, visivel) values (now(), now(), ?::uuid, 'P', 2000, 'd', 'simples', 1, true, true)
                on conflict (id) do nothing""", PLANO);
    }

    /** InstituicaoFactory: "Instituição <n>", ordem n. */
    UUID instituicao() {
        return instituicao("Instituição " + (sequencia + 1));
    }

    UUID instituicao(String nome) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into instituicoes_instituicao (created_at, updated_at, id, nome, ativo, ordem, logo)
                values (now(), now(), ?, ?, true, ?, '')""", id, nome, ++sequencia);
        return id;
    }

    /** PedidoFactory: os campos que o relatorio e a auditoria leem. */
    static final class Pedido {
        String nome = "Fulano de Tal";
        String email = "fulano@exemplo.com";
        String telefone = "+5511999990000";
        String oracao = "Pela saúde da família";
        int valor = 5000;
        String status = "enviada";
        String mp = "MP-1";
        UUID instituicao;
        boolean gratuito;
        OffsetDateTime criado = OffsetDateTime.now();
    }

    UUID pedido(Consumer<Pedido> ajuste) {
        Pedido p = new Pedido();
        ajuste.accept(p);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into orders_pedido (created_at, updated_at, id, nome, email, telefone, sexo, pedido_oracao,
                    oracao_gerada, valor_centavos, canal_entrega, status, plano_id, consent_aceito, eh_gratuito,
                    retry_count, last_error, consent_versao, whatsapp_message_id, cpf_cnpj, device_hash,
                    provider_payment_id, instituicao_id)
                values (?, ?, ?, ?, ?, ?, '', ?, '', ?, 'email', ?, ?::uuid, true, ?, 0, '', '', '', '', '', ?, ?)""",
                p.criado, p.criado, id, fernet.cifrar(p.nome), fernet.cifrar(p.email), fernet.cifrar(p.telefone),
                fernet.cifrar(p.oracao), p.valor, p.status, PLANO, p.gratuito, p.mp, p.instituicao);
        return id;
    }

    /** RepasseFactory: base 5000, 20%, 1000, registrado; sem pedido, cria um (da mesma instituicao). */
    static final class Repasse {
        UUID pedido;
        UUID instituicao;
        int base = 5000;
        int percentual = 20;
        int valor = 1000;
        OffsetDateTime criado = OffsetDateTime.now();
    }

    UUID repasse(UUID instituicao, Consumer<Repasse> ajuste) {
        Repasse r = new Repasse();
        r.instituicao = instituicao;
        ajuste.accept(r);
        UUID pedido = r.pedido != null ? r.pedido : pedido(p -> p.instituicao = instituicao);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into instituicoes_repasse (created_at, updated_at, id, pedido_id, instituicao_id,
                    valor_base_centavos, percentual, valor_centavos, status, transferido_em, comprovante)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'registrado', null, '')""", r.criado, r.criado, id, pedido,
                r.instituicao, r.base, r.percentual, r.valor);
        return id;
    }

    /** _repasse_em: dia (12h) ou data-hora, no fuso de Sao Paulo. */
    static OffsetDateTime em(LocalDate dia) {
        return em(dia.atTime(LocalTime.NOON));
    }

    static OffsetDateTime em(LocalDateTime local) {
        return local.atZone(RelatorioRepasse.SAO_PAULO).toOffsetDateTime();
    }

    /** repasse.marcar_como_transferido(comprovante) do Django, direto no banco. */
    void transferir(UUID repasse, String comprovante) {
        jdbc.update("update instituicoes_repasse set status = 'transferido', transferido_em = now(), comprovante = ? "
                + "where id = ?", comprovante, repasse);
    }

    /** pedido.marcar_como_gratuito(): zera o valor do pedido (o ledger fica). */
    void marcarComoGratuito(UUID pedido) {
        jdbc.update("update orders_pedido set valor_centavos = 0, eh_gratuito = true where id = ?", pedido);
    }

    UUID pedidoDo(UUID repasse) {
        return jdbc.queryForObject("select pedido_id from instituicoes_repasse where id = ?", UUID.class, repasse);
    }

    record Saida(String out, String err, int exit) {}

    /** java -jar clama-api.jar args..., em processo, com dir como diretorio de trabalho. */
    Saida rodar(Path dir, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Comandos.executar(args, new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8),
                new BufferedReader(new StringReader("")), dir, ctx);
        return new Saida(out.toString(UTF_8), err.toString(UTF_8), exit);
    }
}
