package me.clama.api.pedido.comando;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import me.clama.api.comum.Dinheiro;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.texto.TextoPython;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * lgpd_export (mapa §8.3, D1, D4): um JSON com tudo do titular e os avisos do Django. Parte diferente de
 * proposito: o arquivo e lgpd_&lt;AAAAmmdd_HHMMSS&gt;.json (hora de Sao Paulo pelo Clock), sem o e-mail no nome, que
 * derrotaria o m***@ do stdout; --output-dir relativo ao diretorio corrente, criado com 0700. Alem das chaves do Django: cpf_cnpj, eh_gratuito, instituicao e as confirmacoes do freemium de cada
 * pedido; as contas da mesma caixa (sem senha nem hashes); o blog (comentarios com o IP decifrado, reacoes, bans
 * recebidos) e se ha linha na blacklist do freemium. Datas em ISO no fuso de Sao Paulo. Arquivo so do dono (0600).
 */
@Component
public class LgpdExport implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.obrigatoria("--email"), Opcao.valor("--output-dir"));
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss");
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final CampoCriptografado CIFRA = new CampoCriptografado();

    private final BuscaDoTitular busca;
    private final JdbcTemplate jdbc;
    private final HashIdentificador hash;
    private final Clock relogio;

    LgpdExport(BuscaDoTitular busca, JdbcTemplate jdbc, HashIdentificador hash, Clock relogio) {
        this.busca = busca;
        this.jdbc = jdbc;
        this.hash = hash;
        this.relogio = relogio;
    }

    @Override
    public int executar(Chamada c) throws IOException {
        String email = TextoPython.strip(c.valor("--email")).toLowerCase(Locale.ROOT);
        String mascarado = BuscaDoTitular.mascarar(email);
        c.out().println("Buscando pedidos para: " + mascarado);
        BuscaDoTitular.Titular t = busca.buscar(email);
        if (t.vazio()) {
            c.out().println("Nenhum pedido nem conta encontrados para " + mascarado);
            return 0;
        }
        c.out().println("Encontrados " + t.pedidos().size() + " pedido(s) e " + t.contas().size() + " conta(s)");

        OffsetDateTime agora = OffsetDateTime.now(relogio);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("meta", mapa("email", email, "export_date", DatasDrf.dataHora(agora), "total_pedidos",
                t.pedidos().size()));
        List<Map<String, Object>> pedidos = pedidos(t.pedidos());
        List<Map<String, Object>> contas = contas(t.contas());
        export.put("pedidos", pedidos);
        export.put("contas", contas);
        export.put("blog", blog(t.contas()));
        export.put("bloqueio_freemium", bloqueio(email, pedidos, contas));

        Path dir = c.caminho(Objects.requireNonNullElse(c.valor("--output-dir"), "lgpd_exports"));
        // 0700 so se criar (a mascara do processo ainda vale); um diretorio ja existente fica como esta
        Files.createDirectories(dir,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        String carimbo = LocalDateTime.ofInstant(agora.toInstant(), FUSO).format(CARIMBO);
        Path arquivo = dir.resolve("lgpd_" + carimbo + ".json");
        Files.deleteIfExists(arquivo); // mesmo segundo: sobrescreve, como o Django
        Files.createFile(arquivo, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(arquivo, JSON.writeValueAsString(export), UTF_8);

        c.out().println("Export salvo em: " + arquivo);
        c.out().println("ATENÇÃO: Este arquivo contém dados pessoais sensíveis. Manuseie com cuidado e exclua após "
                + "enviar ao titular.");
        return 0;
    }

    /** As chaves do Django na mesma ordem, depois as novas (mapa §8.3). */
    private List<Map<String, Object>> pedidos(List<UUID> ids) {
        Array arr = array("uuid", ids.toArray());
        Map<UUID, List<Map<String, Object>>> tokens = new LinkedHashMap<>();
        jdbc.query("""
                select pedido_id, created_at, expires_at, used_at, host(ip_origem) ip_origem, device_hash
                from freemium_freemiumconfirmationtoken where pedido_id = any(?) order by created_at, id""", rs -> {
            tokens.computeIfAbsent(rs.getObject("pedido_id", UUID.class), k -> new ArrayList<>()).add(mapa(
                    "created_at", data(rs, "created_at"), "expires_at", data(rs, "expires_at"),
                    "used_at", data(rs, "used_at"), "ip_origem", rs.getString("ip_origem"),
                    "device_hash", rs.getString("device_hash")));
        }, arr);
        return jdbc.query("""
                select p.*, host(p.consent_ip) ip, pl.nome plano_nome, pl.valor_centavos plano_valor,
                    i.nome instituicao
                from orders_pedido p join plans_plan pl on pl.id = p.plano_id
                    left join instituicoes_instituicao i on i.id = p.instituicao_id
                where p.id = any(?) order by p.created_at desc, p.id""", (rs, n) -> {
            UUID id = rs.getObject("id", UUID.class);
            Map<String, Object> p = mapa("id", id.toString(), "created_at", data(rs, "created_at"),
                    "updated_at", data(rs, "updated_at"), "nome", claro(rs, "nome"), "email", claro(rs, "email"),
                    "telefone", ouNull(claro(rs, "telefone")), "idade", rs.getObject("idade"),
                    "sexo", ouNull(rs.getString("sexo")), "pedido_oracao", ouNull(claro(rs, "pedido_oracao")),
                    "oracao_gerada", ouNull(claro(rs, "oracao_gerada")),
                    "plano", mapa("nome", rs.getString("plano_nome"),
                            "valor_reais", Dinheiro.centavosParaReaisStr(rs.getInt("plano_valor"))),
                    "valor_reais", Dinheiro.centavosParaReaisStr(rs.getInt("valor_centavos")),
                    "status", rs.getString("status"), "canal_entrega", rs.getString("canal_entrega"),
                    "provider_payment_id", ouNull(rs.getString("provider_payment_id")),
                    "consent", mapa("aceito", rs.getBoolean("consent_aceito"),
                            "versao", ouNull(rs.getString("consent_versao")),
                            "aceito_at", data(rs, "consent_aceito_at"), "ip", ouNull(rs.getString("ip"))));
            p.putAll(mapa("cpf_cnpj", ouNull(claro(rs, "cpf_cnpj")), "eh_gratuito", rs.getBoolean("eh_gratuito"),
                    "instituicao", rs.getString("instituicao"),
                    "confirmacoes_freemium", tokens.getOrDefault(id, List.of())));
            return p;
        }, arr);
    }

    /** Sem password, email_hash, cpf_hash, telefone_hash nem flags de admin. */
    private List<Map<String, Object>> contas(List<Long> ids) {
        return jdbc.query("select * from users_user where id = any(?) order by id", (rs, n) -> mapa(
                "id", rs.getLong("id"), "email", rs.getString("email"), "nome_completo", rs.getString("nome_completo"),
                "cpf_cnpj", ouNull(claro(rs, "cpf_cnpj")), "telefone", ouNull(claro(rs, "telefone")),
                "idade", rs.getObject("idade"), "sexo", ouNull(rs.getString("sexo")),
                "date_joined", data(rs, "date_joined"), "last_login", data(rs, "last_login"),
                "is_active", rs.getBoolean("is_active"), "nome_format_blog", rs.getString("nome_format_blog"),
                "freemium_used_at", data(rs, "freemium_used_at")), array("bigint", ids.toArray()));
    }

    private Map<String, Object> blog(List<Long> contas) {
        Array ids = array("bigint", contas.toArray());
        return mapa("comentarios", jdbc.query("""
                select c.id, e.slug, c.conteudo, c.ip_address, c.is_suspeito, c.created_at
                from blog_comentario c left join blog_postespelho e on e.id = c.post_espelho_id
                where c.customer_id = any(?) order by c.created_at, c.id""", (rs, n) -> mapa(
                        "id", rs.getString("id"), "post_espelho_slug", rs.getString("slug"),
                        "conteudo", rs.getString("conteudo"), "ip_address", ouNull(claro(rs, "ip_address")),
                        "is_suspeito", rs.getBoolean("is_suspeito"), "created_at", data(rs, "created_at")), ids),
                "reacoes", jdbc.query("""
                        select r.id, e.slug, r.tipo, r.created_at
                        from blog_reacao r left join blog_postespelho e on e.id = r.post_espelho_id
                        where r.customer_id = any(?) order by r.created_at, r.id""", (rs, n) -> mapa(
                                "id", rs.getString("id"), "post_espelho_slug", rs.getString("slug"),
                                "tipo", rs.getString("tipo"), "created_at", data(rs, "created_at")), ids),
                "banimentos", jdbc.query("""
                        select motivo, banido_em, revogado_em from blog_customerbanido
                        where customer_id = any(?) order by banido_em, id""", (rs, n) -> mapa(
                                "motivo", rs.getString("motivo"), "banido_em", data(rs, "banido_em"),
                                "revogado_em", data(rs, "revogado_em")), ids));
    }

    /** D4: so se ha linha; os hashes nao saem (o titular nao tem como le-los). */
    private Map<String, Object> bloqueio(String email, List<Map<String, Object>> pedidos,
            List<Map<String, Object>> contas) {
        List<String> cpfs = new ArrayList<>();
        List<String> telefones = new ArrayList<>();
        for (Map<String, Object> m : concat(pedidos, contas)) {
            if (m.get("cpf_cnpj") instanceof String s) {
                cpfs.add(hash.hashCpfCnpj(s));
            }
            if (m.get("telefone") instanceof String s) {
                telefones.add(hash.hashTelefone(s));
            }
        }
        return mapa("email", existe("email_hash = ?", hash.hashEmail(email)),
                "cpf", existe("cpf_hash = any(?)", array("text", cpfs.toArray())),
                "telefone", existe("telefone_hash = any(?)", array("text", telefones.toArray())));
    }

    private boolean existe(String onde, Object arg) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from freemium_freemiumblacklist where "
                + onde + ")", Boolean.class, arg));
    }

    private static List<Map<String, Object>> concat(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        List<Map<String, Object>> todos = new ArrayList<>(a);
        todos.addAll(b);
        return todos;
    }

    /** Coluna cifrada decifrada como o model (texto puro legado volta cru). */
    private static String claro(ResultSet rs, String coluna) throws SQLException {
        return CIFRA.convertToEntityAttribute(rs.getString(coluna));
    }

    private static String data(ResultSet rs, String coluna) throws SQLException {
        return DatasDrf.dataHora(rs.getObject(coluna, OffsetDateTime.class));
    }

    /** O "valor or None" do Django. */
    private static String ouNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    /** LinkedHashMap que aceita null (Map.of nao aceita), na ordem dada. */
    private static Map<String, Object> mapa(Object... chaveValor) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < chaveValor.length; i += 2) {
            m.put((String) chaveValor[i], chaveValor[i + 1]);
        }
        return m;
    }

    private Array array(String tipo, Object[] valores) {
        return jdbc.execute((ConnectionCallback<Array>) con -> con.createArrayOf(tipo, valores));
    }
}
