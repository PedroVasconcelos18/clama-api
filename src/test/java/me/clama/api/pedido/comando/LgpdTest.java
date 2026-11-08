package me.clama.api.pedido.comando;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.comando.Comandos;
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.pedido.ExclusaoDePedido;
import me.clama.api.freemium.JobConfirmacaoFreemium;
import me.clama.api.oracao.JobAlertaAdmin;
import me.clama.api.oracao.JobEnviarOracao;
import me.clama.api.oracao.JobGerarOracao;
import me.clama.api.oracao.JobReenviarOracao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * lgpd_export e lgpd_delete (mapa §8, D1-D4). O Django nunca acha nada (filtra e-mail cifrado contra texto puro), entao
 * nao ha contrato gravado: os comandos sao correcoes deliberadas, testadas so aqui.
 */
@SpringBootTest
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class LgpdTest {
    static final String PLANO = "55555555-5555-4555-8555-0000000000b1";
    static final String EMAIL = "maria.silva@exemplo.com";
    static final Instant AGORA = Instant.parse("2026-11-07T15:04:05Z"); // 12:04:05 em Sao Paulo

    @Autowired JdbcTemplate jdbc;
    @Autowired Fernet fernet;
    @Autowired HashIdentificador hash;
    @Autowired UsuarioRepository usuarios;
    @Autowired ApplicationContext ctx;
    @Autowired RelogioDeTeste.Fixavel relogio;
    @Autowired TransactionTemplate tx;
    @Autowired ExclusaoDePedido exclusao;

    @TempDir Path dir;
    UUID espelho;
    UUID casa;
    long admin;

    @BeforeEach
    void preparar() {
        limpar();
        relogio.fixar(AGORA);
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade,
                    ordem, ativo, visivel) values (now(), now(), ?::uuid, 'Oração Simples', 2000, 'd', 'simples', 1,
                    true, true) on conflict (id) do nothing""", PLANO);
        espelho = UUID.randomUUID();
        jdbc.update("""
                insert into blog_postespelho (id, created_at, updated_at, wp_post_id, slug, titulo, status, url)
                values (?, now(), now(), 1, 'fe-e-esperanca', 'P', 'publish', 'https://clama.me/blog/p')""", espelho);
        casa = UUID.randomUUID();
        jdbc.update("""
                insert into instituicoes_instituicao (created_at, updated_at, id, nome, ativo, ordem, logo)
                values (now(), now(), ?, 'Casa de Apoio', true, 1, '')""", casa);
        jdbc.update("insert into django_content_type (app_label, model) values ('users', 'user'), "
                + "('orders', 'pedido') on conflict do nothing");
        admin = usuario("admin@clama.me");
        jdbc.update("update users_user set is_clama_admin = true, is_staff = true where id = ?", admin);
    }

    @AfterEach
    void soltar() {
        limpar();
        relogio.fixar(null);
    }

    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE scheduled_tasks, instituicoes_repasse, instituicoes_instituicao, payments_webhookevento, "
                + "freemium_freemiumblacklist, freemium_freemiumconfirmationtoken, orders_pedido, blog_postespelho, "
                + "django_admin_log, users_user CASCADE");
    }

    // ---------- fixtures ----------

    long usuario(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("argon2$segredo");
        u.setNomeCompleto("Maria Silva");
        u.setCpfCnpj("52998224725");
        u.setTelefone("+5511988887777");
        return usuarios.saveAndFlush(u).getId();
    }

    UUID pedido(String email, Long user) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into orders_pedido (created_at, updated_at, id, nome, email, telefone, cpf_cnpj, sexo, idade,
                    pedido_oracao, oracao_gerada, valor_centavos, canal_entrega, status, plano_id, consent_aceito,
                    consent_versao, consent_ip, eh_gratuito, retry_count, last_error, whatsapp_message_id,
                    device_hash, provider_payment_id, user_id, instituicao_id)
                values ('2026-10-01T12:00:00Z', '2026-10-01T12:00:00Z', ?, ?, ?, ?, ?, 'feminino', 41, ?, ?, 5000,
                    'email', 'enviada', ?::uuid, true, 'v1', cast('203.0.113.9' as inet), false, 0, 'erro p/ x@y.com',
                    '', 'fp-123', 'MP-1', ?, ?)""", id, fernet.cifrar("Maria Silva"), fernet.cifrar(email),
                fernet.cifrar("+5511988887777"), fernet.cifrar("52998224725"), fernet.cifrar("Pela minha mãe"),
                fernet.cifrar("Senhor, cuida dela."), PLANO, user, casa);
        return id;
    }

    void repasse(UUID pedido) {
        jdbc.update("""
                insert into instituicoes_repasse (created_at, updated_at, id, pedido_id, instituicao_id,
                    valor_base_centavos, percentual, valor_centavos, status, transferido_em, comprovante)
                values (now(), now(), gen_random_uuid(), ?, ?, 5000, 20, 1000, 'registrado', null, '')""", pedido,
                casa);
    }

    void token(UUID pedido) {
        jdbc.update("""
                insert into freemium_freemiumconfirmationtoken (created_at, updated_at, id, token, expires_at, used_at,
                    ip_origem, device_hash, pedido_id)
                values (now(), now(), gen_random_uuid(), ?, now() + interval '1 day', null,
                    cast('198.51.100.4' as inet), 'fp-123', ?)""", "tok-" + pedido, pedido);
    }

    UUID webhook(UUID pedido) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into payments_webhookevento (created_at, updated_at, id, provider, external_event_id,
                    event_type, payload, status, error_message, pedido_id)
                values (now(), now(), ?, 'mercadopago', ?, 'payment', '{}', 'processado', '', ?)""", id,
                "ev-" + id, pedido);
        return id;
    }

    void jobs(UUID pedido) {
        Map<String, String> instancias = Map.of(JobGerarOracao.NOME, JobGerarOracao.instancia(pedido),
                JobEnviarOracao.NOME, JobEnviarOracao.instancia(pedido), JobReenviarOracao.NOME,
                JobReenviarOracao.instancia(pedido), JobAlertaAdmin.NOME, JobAlertaAdmin.instancia(pedido),
                JobConfirmacaoFreemium.NOME, JobConfirmacaoFreemium.instancia(pedido));
        instancias.forEach((nome, instancia) -> jdbc.update("""
                insert into scheduled_tasks (task_name, task_instance, execution_time, picked, version)
                values (?, ?, now() + interval '1 hour', false, 1)""", nome, instancia));
        assertThat(instancias.values()).contains("alerta-admin/" + pedido, "confirmacao-freemium/" + pedido);
    }

    void blacklist(String email) {
        jdbc.update("""
                insert into freemium_freemiumblacklist (id, created_at, updated_at, cpf_hash, email_hash)
                values (gen_random_uuid(), now(), now(), ?, ?)""", hash.hashCpfCnpj("52998224725"),
                hash.hashEmail(email));
    }

    void comentario(long user) {
        jdbc.update("""
                insert into blog_comentario (id, created_at, updated_at, customer_id, post_espelho_id, conteudo,
                    ip_address, is_suspeito) values (gen_random_uuid(), now(), now(), ?, ?, 'Amém', ?, false)""",
                user, espelho, fernet.cifrar("192.0.2.33"));
    }

    void reacao(long user) {
        jdbc.update("""
                insert into blog_reacao (id, created_at, updated_at, tipo, customer_id, post_espelho_id)
                values (gen_random_uuid(), now(), now(), 'like', ?, ?)""", user, espelho);
    }

    void ban(long banidoPor, long customer) {
        jdbc.update("""
                insert into blog_customerbanido (created_at, updated_at, id, motivo, banido_em, banido_por_id,
                    customer_id) values (now(), now(), gen_random_uuid(), 'Ofensa', now(), ?, ?)""", banidoPor,
                customer);
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject("select count(*) from " + sql, Integer.class, args);
    }

    record Saida(String out, String err, int exit) {}

    Saida rodar(String stdin, String... args) {
        return rodar(new BufferedReader(new StringReader(stdin)), args);
    }

    Saida rodar(BufferedReader stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Comandos.executar(args, new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8),
                stdin, dir, ctx);
        return new Saida(out.toString(UTF_8), err.toString(UTF_8), exit);
    }

    Saida deletar(String stdin, String... extras) {
        String[] args = new String[extras.length + 3];
        args[0] = "lgpd_delete";
        args[1] = "--email";
        args[2] = EMAIL;
        System.arraycopy(extras, 0, args, 3, extras.length);
        return rodar(stdin, args);
    }

    /** A pessoa completa do lgpdDeleteCompleto; devolve [conta, pedido sem repasse, pedido com repasse]. */
    record Pessoa(long conta, UUID livre, UUID comRepasse, UUID webhook) {}

    Pessoa pessoa() {
        long conta = usuario(EMAIL);
        UUID livre = pedido(EMAIL, conta);
        UUID comRepasse = pedido(EMAIL, conta);
        repasse(comRepasse);
        comentario(conta);
        comentario(conta);
        reacao(conta);
        ban(admin, conta);
        token(livre);
        token(comRepasse);
        UUID ev = webhook(livre);
        jobs(livre);
        jobs(comRepasse);
        blacklist(EMAIL);
        return new Pessoa(conta, livre, comRepasse, ev);
    }

    /** Fotografia do que o delete mexe: igual antes e depois = nada mudou. */
    String estado() {
        return jdbc.queryForList("""
                select (select count(*) from users_user) u, (select count(*) from orders_pedido) p,
                    (select string_agg(email || nome || coalesce(user_id::text, '-'), ',' order by id)
                       from orders_pedido) pii,
                    (select count(*) from freemium_freemiumconfirmationtoken) t,
                    (select count(*) from payments_webhookevento where pedido_id is not null) w,
                    (select count(*) from scheduled_tasks) j, (select count(*) from blog_comentario) c,
                    (select count(*) from blog_reacao) r, (select count(*) from blog_customerbanido) b,
                    (select count(*) from freemium_freemiumblacklist) bl,
                    (select count(*) from django_admin_log) a""").toString();
    }

    // ---------- lgpd_delete ----------

    @Test
    void lgpdDeleteCompleto() throws Exception {
        Pessoa p = pessoa();
        long outra = usuario("outra@exemplo.com");
        UUID alheio = pedido("outra@exemplo.com", outra);

        Saida s = deletar("DELETAR\n", "--confirm", "--autor=" + admin);

        assertThat(s.exit()).as(s.err()).isZero(); // commit sem FK violada (as FKs sao DEFERRED: estouram no commit)
        assertThat(contar("users_user where id = ?", p.conta())).isZero();
        assertThat(contar("orders_pedido where id = ?", p.livre())).isZero();
        // com repasse: anonimizado, financeiro e vinculo ao repasse intactos
        Map<String, Object> anon = jdbc.queryForMap("select * from orders_pedido where id = ?", p.comRepasse());
        for (String campo : List.of("nome", "email", "telefone", "cpf_cnpj", "pedido_oracao", "oracao_gerada")) {
            assertThat(fernet.decifrar((String) anon.get(campo))).as(campo).isEmpty();
        }
        assertThat(anon).containsEntry("consent_ip", null).containsEntry("device_hash", "")
                .containsEntry("user_id", null).containsEntry("idade", null).containsEntry("sexo", "")
                .containsEntry("last_error", "").containsEntry("valor_centavos", 5000)
                .containsEntry("status", "enviada").containsEntry("provider_payment_id", "MP-1")
                .containsEntry("instituicao_id", casa);
        assertThat(contar("instituicoes_repasse where pedido_id = ?", p.comRepasse())).isOne();
        assertThat(contar("freemium_freemiumconfirmationtoken")).isZero();
        assertThat(jdbc.queryForObject("select pedido_id from payments_webhookevento where id = ?", UUID.class,
                p.webhook())).isNull();
        assertThat(contar("scheduled_tasks")).isZero();
        assertThat(contar("blog_comentario")).isZero();
        assertThat(contar("blog_reacao")).isZero();
        assertThat(contar("blog_customerbanido")).isZero();
        assertThat(contar("freemium_freemiumblacklist")).isOne(); // D4: fica
        // alheio intocado
        assertThat(contar("orders_pedido where id = ? and user_id = ?", alheio, outra)).isOne();
        // --autor: pedido apagado, pedido anonimizado, conta excluida
        assertThat(jdbc.queryForList("select action_flag, object_id from django_admin_log where user_id = ? "
                + "order by action_flag, object_id", admin)).extracting(m -> m.get("action_flag") + " " + m.get("object_id"))
                .containsExactlyInAnyOrder("3 " + p.livre(), "2 " + p.comRepasse(), "3 " + p.conta());

        assertThat(s.out()).contains("  - " + p.livre() + " | 2026-10-01 | enviada | R$ 50,00\n",
                "  - " + p.comRepasse() + " | 2026-10-01 | enviada | R$ 50,00 | repasse: anonimiza\n",
                "  conta " + p.conta() + " (comentários: 2, reações: 1)\n",
                "Digite 'DELETAR' para confirmar: ",
                "Deletados 1 pedido(s) permanentemente.\n",
                "Anonimizados 1 pedido(s) com repasse (o registro contábil fica).\n",
                "Excluída(s) 1 conta(s).\n",
                "A ação foi registrada no log e Sentry para fins de auditoria.\n");
    }

    @Test
    void buscaPorVarreduraAchaPedidoOrfao() {
        UUID orfao = pedido("Maria.Silva@Exemplo.com ", null); // sem conta, caixa e espaco diferentes
        UUID alheio = pedido("outra@exemplo.com", null);

        Saida s = deletar("DELETAR\n", "--confirm");

        assertThat(s.exit()).as(s.err()).isZero();
        assertThat(contar("orders_pedido where id = ?", orfao)).isZero();
        assertThat(contar("orders_pedido where id = ?", alheio)).isOne();
        assertThat(s.out()).contains("Encontrados 1 pedido(s) e 0 conta(s):", "Deletados 1 pedido(s)");
    }

    /** Gmail canonico: a mesma caixa e o mesmo titular (pontos, +tag e googlemail). Pedido da conta entra pelo user_id. */
    @Test
    void gmailCanonico() {
        long joao = usuario("joao@gmail.com");
        UUID orfao = pedido("j.oao+doacao@gmail.com", null);
        UUID daConta = pedido("email-antigo@exemplo.com", joao); // e-mail do pedido nao casa; casa pela conta
        UUID alheio = pedido("joao@outlook.com", null);

        Saida s = rodar("", "lgpd_delete", "--email", " J.O.A.O@GoogleMail.com");

        assertThat(s.exit()).as(s.err()).isZero();
        assertThat(s.out()).contains(orfao.toString(), daConta.toString(), "  conta " + joao + " ")
                .doesNotContain(alheio.toString());
    }

    @Test
    void dryRunNaoMudaNada() {
        pessoa();
        String antes = estado();

        Saida s = deletar("DELETAR\n");

        assertThat(s.exit()).isZero();
        assertThat(estado()).isEqualTo(antes);
        assertThat(s.out()).endsWith("\nMODO DRY RUN: Nenhum dado foi deletado.\n"
                + "Execute com --confirm para deletar permanentemente.\n").doesNotContain("Digite");
    }

    @Test
    void palavraErradaOuSemRespostaNaoMudaNada() {
        pessoa();
        String antes = estado();

        Saida errada = deletar("deletar\n", "--confirm");
        assertThat(errada.exit()).isZero();
        assertThat(errada.out()).endsWith("Digite 'DELETAR' para confirmar: Operação cancelada.\n");
        assertThat(estado()).isEqualTo(antes);

        Saida eof = deletar("", "--confirm");
        assertThat(eof.exit()).isOne();
        assertThat(eof.err()).startsWith("CommandError: Sem resposta no stdin");
        assertThat(estado()).isEqualTo(antes);
    }

    /** Guarda do ExclusaoDeUsuario (PROTECT de CustomerBanido.banido_por): exit 1 e rollback de tudo. */
    @Test
    void guardaBanidoPorExit1ENadaMuda() {
        Pessoa p = pessoa();
        ban(p.conta(), usuario("alguem@exemplo.com"));
        String antes = estado();

        Saida s = deletar("DELETAR\n", "--confirm", "--autor=" + admin);

        assertThat(s.exit()).isOne();
        assertThat(s.err()).startsWith("CommandError: Essa conta baniu alguém no blog");
        assertThat(estado()).isEqualTo(antes);
    }

    /** Ultimo admin ativo: a guarda vale tambem no comando (nao ha "si mesmo" no comando; essa guarda e da tela). */
    @Test
    void guardaUltimoAdminExit1() {
        jdbc.update("update users_user set is_clama_admin = false where id = ?", admin);
        long conta = usuario(EMAIL);
        jdbc.update("update users_user set is_clama_admin = true where id = ?", conta);
        pedido(EMAIL, conta);
        String antes = estado();

        Saida s = deletar("DELETAR\n", "--confirm");

        assertThat(s.exit()).isOne();
        assertThat(s.err()).startsWith("CommandError: Essa é a última conta de admin ativa.");
        assertThat(estado()).isEqualTo(antes);
    }

    @Test
    void pedidoEmProcessamentoRecusaTudoEExit1() {
        Pessoa p = pessoa();
        for (String status : List.of("pago", "gerando_oracao", "oracao_gerada", "aguardando_reenvio")) {
            jdbc.update("update orders_pedido set status = ? where id = ?", status, p.comRepasse());
            String antes = estado();

            Saida s = deletar("DELETAR\n", "--confirm");

            assertThat(s.exit()).as(status).isOne();
            assertThat(s.err()).as(status).startsWith("CommandError: Pedido em processamento, tente mais tarde");
            assertThat(estado()).as(status).isEqualTo(antes);
        }
    }

    /** O titular ganha um pedido entre a listagem e a confirmacao (a resposta do stdin chega tarde): aborta, nada muda. */
    @Test
    void conjuntoQueMudouDepoisDaListagemAbortaSemApagarNada() {
        pessoa();
        String[] antes = {null};
        BufferedReader lento = new BufferedReader(new StringReader("DELETAR\n")) {
            @Override
            public String readLine() throws java.io.IOException {
                pedido(EMAIL, null); // pedido novo, sem conta
                antes[0] = estado();
                return super.readLine();
            }
        };

        Saida s = rodar(lento, "lgpd_delete", "--email", EMAIL, "--confirm");

        assertThat(s.exit()).as(s.out()).isOne();
        assertThat(s.err()).startsWith("CommandError: Os pedidos ou contas do titular mudaram desde a listagem");
        assertThat(estado()).isEqualTo(antes[0]);
    }

    @Test
    void autorNaoPodeSerAPropriaContaExcluida() {
        Pessoa p = pessoa();
        jdbc.update("update users_user set is_staff = true where id = ?", p.conta());
        String antes = estado();

        Saida s = deletar("DELETAR\n", "--confirm", "--autor=" + p.conta());

        assertThat(s.exit()).isOne();
        assertThat(s.err()).startsWith("CommandError: --autor");
        assertThat(estado()).isEqualTo(antes);
    }

    /** E-mail vazio casaria com todo pedido ja anonimizado (cifra de ""): recusado antes de buscar. */
    @Test
    void emailInvalidoERecusado() {
        UUID p = pedido("", null);

        assertThat(rodar("DELETAR\n", "lgpd_delete", "--email", " ", "--confirm").exit()).isOne();
        assertThat(rodar("", "lgpd_export", "--email", "sem-arroba").exit()).isOne();
        assertThat(contar("orders_pedido where id = ?", p)).isOne();
    }

    @Test
    void nadaEncontrado() {
        Saida s = deletar("", "--confirm");
        assertThat(s.exit()).isZero();
        assertThat(s.out()).isEqualTo("Buscando pedidos para: m***@exemplo.com\n"
                + "Nenhum pedido nem conta encontrados para m***@exemplo.com\n");
    }

    /** Log, Sentry e stdout sem o e-mail (o Django mandava o e-mail em claro ao Sentry e ao log). */
    @Test
    void logESentrySemEmail() {
        pessoa();
        Logger raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        raiz.addAppender(logs);
        TransportEmMemoria sentry = new TransportEmMemoria();
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(sentry.fabrica());
        });
        Saida s;
        try {
            s = deletar("DELETAR\n", "--confirm");
            rodar("", "lgpd_export", "--email", EMAIL);
        } finally {
            raiz.detachAppender(logs);
            Sentry.close();
        }

        assertThat(s.exit()).as(s.err()).isZero();
        List<String> pistas = List.of(EMAIL, "maria.silva", "mariasilva");
        assertThat(logs.list).isNotEmpty().allSatisfy(e -> {
            String linha = e.getFormattedMessage() + " " + e.getMDCPropertyMap();
            pistas.forEach(x -> assertThat(linha).doesNotContainIgnoringCase(x));
        });
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .startsWith("lgpd_delete executado (pedidos_apagados=1, pedidos_anonimizados=1, contas_excluidas=1"));
        assertThat(sentry.eventos).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(SentryLevel.INFO);
            String msg = e.getMessage().getFormatted();
            assertThat(msg).isEqualTo("LGPD delete: 1 pedido(s) apagado(s), 1 anonimizado(s), 1 conta(s) excluída(s)");
        });
        pistas.forEach(x -> assertThat(s.out()).doesNotContainIgnoringCase(x));
    }

    /** Producao antes da virada: o Java nunca rodou e scheduled_tasks nao existe (o modo comando nao roda Flyway). */
    @Test
    void semScheduledTasksAntesDoPrimeiroDeploy() {
        UUID p = pedido(EMAIL, null);
        tx.executeWithoutResult(st -> {
            jdbc.execute("alter table scheduled_tasks rename to scheduled_tasks_fora"); // DDL volta no rollback
            assertThat(exclusao.excluir(List.of(p)).apagados()).containsExactly(p);
            st.setRollbackOnly();
        });
        assertThat(contar("scheduled_tasks")).isZero();
        assertThat(contar("orders_pedido where id = ?", p)).isOne();
    }

    // ---------- lgpd_export ----------

    @Test
    void exportConteudoENome() throws Exception {
        Pessoa p = pessoa();
        UUID orfao = pedido("MARIA.SILVA@exemplo.com", null);
        jdbc.update("update orders_pedido set created_at = '2026-10-02T12:00:00Z' where id = ?", orfao);

        Saida s = rodar("", "lgpd_export", "--email", "Maria.Silva@Exemplo.com", "--output-dir", "saida");

        assertThat(s.exit()).as(s.err()).isZero();
        Path arquivo = dir.resolve("saida/lgpd_20261107_120405.json");
        assertThat(arquivo).exists().satisfies(a -> assertThat(a.toString()).doesNotContain("maria"));
        assertThat(Files.getPosixFilePermissions(dir.resolve("saida"))).extracting(Object::toString)
                .containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE");
        assertThat(s.out()).isEqualTo("Buscando pedidos para: m***@exemplo.com\n"
                + "Encontrados 3 pedido(s) e 1 conta(s)\n"
                + "Export salvo em: " + arquivo + "\n"
                + "ATENÇÃO: Este arquivo contém dados pessoais sensíveis. Manuseie com cuidado e exclua após enviar ao "
                + "titular.\n");
        String texto = Files.readString(arquivo, UTF_8);
        assertThat(texto).doesNotContain("argon2", "password", "email_hash", "tok-").contains("Oração Simples");
        assertThat(Files.getPosixFilePermissions(arquivo)).extracting(Object::toString)
                .containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE");

        JsonNode j = JsonMapper.builder().build().readTree(texto);
        assertThat(j.at("/meta/email").asString()).isEqualTo("maria.silva@exemplo.com");
        assertThat(j.at("/meta/export_date").asString()).isEqualTo("2026-11-07T12:04:05-03:00");
        assertThat(j.at("/meta/total_pedidos").asInt()).isEqualTo(3);

        JsonNode pedidos = j.get("pedidos");
        assertThat(pedidos.get(0).get("id").asString()).isEqualTo(orfao.toString()); // mais novo primeiro
        JsonNode livre = null;
        for (JsonNode x : pedidos) {
            if (x.get("id").asString().equals(p.livre().toString())) {
                livre = x;
            }
        }
        assertThat(livre).isNotNull();
        assertThat(livre.get("nome").asString()).isEqualTo("Maria Silva");
        assertThat(livre.get("email").asString()).isEqualTo(EMAIL);
        assertThat(livre.get("telefone").asString()).isEqualTo("+5511988887777");
        assertThat(livre.get("cpf_cnpj").asString()).isEqualTo("52998224725");
        assertThat(livre.get("idade").asInt()).isEqualTo(41);
        assertThat(livre.get("pedido_oracao").asString()).isEqualTo("Pela minha mãe");
        assertThat(livre.get("oracao_gerada").asString()).isEqualTo("Senhor, cuida dela.");
        assertThat(livre.at("/plano/nome").asString()).isEqualTo("Oração Simples");
        assertThat(livre.at("/plano/valor_reais").asString()).isEqualTo("R$ 20,00");
        assertThat(livre.get("valor_reais").asString()).isEqualTo("R$ 50,00");
        assertThat(livre.get("status").asString()).isEqualTo("enviada");
        assertThat(livre.get("instituicao").asString()).isEqualTo("Casa de Apoio");
        assertThat(livre.get("created_at").asString()).isEqualTo("2026-10-01T09:00:00-03:00");
        assertThat(livre.at("/consent/ip").asString()).isEqualTo("203.0.113.9");
        assertThat(livre.at("/consent/versao").asString()).isEqualTo("v1");
        assertThat(livre.at("/confirmacoes_freemium/0/ip_origem").asString()).isEqualTo("198.51.100.4");
        assertThat(livre.at("/confirmacoes_freemium/0/device_hash").asString()).isEqualTo("fp-123");

        JsonNode conta = j.at("/contas/0");
        assertThat(conta.get("id").asLong()).isEqualTo(p.conta());
        assertThat(conta.get("cpf_cnpj").asString()).isEqualTo("52998224725");
        assertThat(conta.get("telefone").asString()).isEqualTo("+5511988887777");
        assertThat(j.get("contas")).hasSize(1);

        assertThat(j.at("/blog/comentarios")).hasSize(2);
        assertThat(j.at("/blog/comentarios/0/ip_address").asString()).isEqualTo("192.0.2.33");
        assertThat(j.at("/blog/comentarios/0/post_espelho_slug").asString()).isEqualTo("fe-e-esperanca");
        assertThat(j.at("/blog/reacoes/0/tipo").asString()).isEqualTo("like");
        assertThat(j.at("/blog/banimentos/0/motivo").asString()).isEqualTo("Ofensa");

        assertThat(j.at("/bloqueio_freemium/email").asBoolean()).isTrue();
        assertThat(j.at("/bloqueio_freemium/cpf").asBoolean()).isTrue();
        assertThat(j.at("/bloqueio_freemium/telefone").asBoolean()).isFalse();
    }

    @Test
    void exportSemNadaNaoGravaArquivo() throws Exception {
        Saida s = rodar("", "lgpd_export", "--email", EMAIL);
        assertThat(s.exit()).isZero();
        assertThat(s.out()).endsWith("Nenhum pedido nem conta encontrados para m***@exemplo.com\n");
        assertThat(dir.resolve("lgpd_exports")).doesNotExist();
    }
}
