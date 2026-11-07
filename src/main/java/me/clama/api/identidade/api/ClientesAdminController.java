package me.clama.api.identidade.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaLimitOffset;
import me.clama.api.comum.api.ParametrosDeQuery;
import me.clama.api.comum.auditoria.RegistroDeAuditoria;
import me.clama.api.comum.email.ValidadorDeEmail;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.conta.ProvisionamentoDeConta;
import me.clama.api.identidade.ExclusaoDeUsuario;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.identidade.senha.ValidadorDeSenha;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * AdminCustomerListView e AdminCustomerDetailView (admin_customer_views.py): acesso admin e do SegurancaConfig. As
 * tabelas do blog sao lidas em SQL puro (sem entidade). cpf_cnpj e telefone so saem decifrados no detalhe (entidade).
 * Escritas (mapa 7 §4.4): criar admin, PATCH de is_clama_admin/is_active, definir senha e excluir, so com a authority
 * SUPERUSER (D3, SegurancaConfig). is_superuser e e-mail nunca mudam por aqui (D7, D18); senha nunca sai.
 */
@RestController
class ClientesAdminController {
    private static final String ITEM = "/api/admin/customers/{id:[0-9]+}/";
    private static final String APP = "users";
    private static final String MODEL = "user";
    private static final Object ADICIONADO = List.of(Map.of("added", Map.of()));
    /** AdminPasswordChangeForm.changed_data e ["password"]. */
    private static final Object SENHA_MUDOU = List.of(Map.of("changed", Map.of("fields", List.of("password"))));
    private static final String CONTAGENS = """
            (select count(*) from orders_pedido o where o.user_id = u.id) as total_pedidos,
            (select count(*) from orders_pedido o where o.user_id = u.id and not o.eh_gratuito) as pedidos_pagos,
            (select count(*) from orders_pedido o where o.user_id = u.id and o.eh_gratuito) as pedidos_gratuitos,
            (select count(*) from blog_comentario c where c.customer_id = u.id) as total_comentarios""";
    /** Ban ativo mais recente (defensivo: a constraint nao impede varios). */
    private static final String BAN = """
            left join lateral (select b.motivo, b.banido_em from blog_customerbanido b
                where b.customer_id = u.id and b.revogado_em is null order by b.banido_em desc limit 1) ban on true""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final UsuarioRepository usuarios;
    private final SenhaDjango senhas;
    private final ValidadorDeSenha validador;
    private final ServicoDeTokens tokens;
    private final ProvisionamentoDeConta contas;
    private final ExclusaoDeUsuario exclusao;
    private final RegistroDeAuditoria auditoria;
    private final Clock relogio;

    ClientesAdminController(JdbcTemplate jdbc, TransactionTemplate tx, UsuarioRepository usuarios, SenhaDjango senhas,
            ValidadorDeSenha validador, ServicoDeTokens tokens, ProvisionamentoDeConta contas,
            ExclusaoDeUsuario exclusao, RegistroDeAuditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.usuarios = usuarios;
        this.senhas = senhas;
        this.validador = validador;
        this.tokens = tokens;
        this.contas = contas;
        this.exclusao = exclusao;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    record Item(long id, String email, String nome_completo, String date_joined, String freemium_used_at,
            boolean is_clama_admin, long total_pedidos, long pedidos_pagos, long pedidos_gratuitos,
            long total_comentarios, boolean is_banned, String motivo_ban, String banido_em) {}

    record Banimento(UUID id, String motivo, String banido_em, String banido_por_email, String revogado_em,
            String revogado_por_email) {}

    record Detalhe(long id, String email, String nome_completo, String date_joined, String last_login,
            boolean is_active, boolean is_clama_admin, String cpf_cnpj, String telefone, String nome_format_blog,
            String freemium_used_at, long total_pedidos, long pedidos_pagos, long pedidos_gratuitos,
            long total_comentarios, boolean is_banned, List<Banimento> banimentos) {}

    /** query_params.get: o ultimo valor. */
    private static String param(HttpServletRequest req, String nome) {
        return java.util.Objects.toString(ParametrosDeQuery.ultimo(req, nome), "");
    }

    @GetMapping("/api/admin/customers/")
    Map<String, Object> listar(HttpServletRequest req) {
        PaginaLimitOffset pagina = PaginaLimitOffset.de(req);
        List<String> onde = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        String q = TextoPython.strip(param(req, "q"));
        if (!q.isEmpty()) {
            onde.add("(u.email ilike ? or u.nome_completo ilike ?)");
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        switch (param(req, "banned")) {
            case "true" -> onde.add("ban.motivo is not null");
            case "false" -> onde.add("ban.motivo is null");
            default -> { }
        }
        switch (param(req, "freemium")) {
            case "true" -> onde.add("u.freemium_used_at is not null");
            case "false" -> onde.add("u.freemium_used_at is null");
            default -> { }
        }
        // como banned e freemium: valor fora de true/false e ignorado (o Django ignora o parametro inteiro)
        switch (param(req, "admin")) {
            case "true" -> onde.add("u.is_clama_admin");
            case "false" -> onde.add("not u.is_clama_admin");
            default -> { }
        }
        String de = " from users_user u " + BAN + (onde.isEmpty() ? "" : " where " + String.join(" and ", onde));

        return tx.execute(st -> {
            Long count = jdbc.queryForObject("select count(*)" + de, Long.class, args.toArray());
            List<Object> argsPagina = new ArrayList<>(args);
            argsPagina.add(pagina.limit());
            argsPagina.add(pagina.offset());
            List<Item> itens = jdbc.query("""
                    select u.id, u.email, u.nome_completo, u.date_joined, u.freemium_used_at, u.is_clama_admin,
                    """ + CONTAGENS + ", ban.motivo, ban.banido_em" + de
                    + " order by u.date_joined desc, u.id desc limit ? offset ?",
                    (rs, n) -> {
                        String motivo = rs.getString("motivo");
                        return new Item(rs.getLong("id"), rs.getString("email"), rs.getString("nome_completo"),
                                data(rs, "date_joined"), data(rs, "freemium_used_at"), rs.getBoolean("is_clama_admin"),
                                rs.getLong("total_pedidos"), rs.getLong("pedidos_pagos"),
                                rs.getLong("pedidos_gratuitos"), rs.getLong("total_comentarios"), motivo != null,
                                motivo, data(rs, "banido_em"));
                    }, argsPagina.toArray());
            return pagina.resposta(count, itens);
        });
    }

    private static String data(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        return DatasDrf.dataHora(rs.getObject(coluna, java.time.OffsetDateTime.class));
    }

    /** O get_object do Django captura qualquer excecao (inclusive id fora do bigint) e vira 404. */
    private static long id(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw naoEncontrado();
        }
    }

    @GetMapping(ITEM)
    Detalhe detalhe(@PathVariable String id) {
        long n = id(id);
        return tx.execute(st -> {
            Usuario u = usuarios.findById(n).orElseThrow(ClientesAdminController::naoEncontrado);
            long[] c = jdbc.queryForObject("select " + CONTAGENS + " from users_user u where u.id = ?",
                    (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, n);
            List<Banimento> bans = jdbc.query("""
                    select b.id, b.motivo, b.banido_em, p.email as por, b.revogado_em, r.email as revogado_por
                    from blog_customerbanido b join users_user p on p.id = b.banido_por_id
                    left join users_user r on r.id = b.revogado_por_id
                    where b.customer_id = ? order by b.banido_em desc""",
                    (rs, i) -> new Banimento(rs.getObject("id", UUID.class), rs.getString("motivo"),
                            data(rs, "banido_em"), rs.getString("por"), data(rs, "revogado_em"),
                            rs.getString("revogado_por")), n);
            boolean banido = bans.stream().anyMatch(b -> b.revogado_em() == null);
            return new Detalhe(u.getId(), u.getEmail(), u.getNomeCompleto(), DatasDrf.dataHora(u.getDateJoined()),
                    DatasDrf.dataHora(u.getLastLogin()), u.isActive(), u.isClamaAdmin(), u.getCpfCnpj(),
                    u.getTelefone(), u.getNomeFormatBlog(), DatasDrf.dataHora(u.getFreemiumUsedAt()), c[0], c[1], c[2],
                    c[3], banido, bans);
        });
    }

    /**
     * Criar admin: e-mail como o create_clama_admin (strip + lower), is_staff junto (D7), nunca superusuario. O lock
     * conta-doador: serializa com o provisionamento de doador e a confirmacao do freemium do mesmo e-mail.
     */
    @PostMapping("/api/admin/customers/")
    ResponseEntity<Detalhe> criar(@RequestBody(required = false) JsonNode corpo, @AuthenticationPrincipal Usuario eu) {
        CamposDrf campos = new CamposDrf(corpo);
        String digitado = campos.texto("email", true, CamposDrf.BRANCO, CamposDrf.maxLength(254),
                s -> ValidadorDeEmail.valido(s) ? List.of() : List.of("Insira um endereço de email válido."));
        String email = digitado == null ? null : digitado.toLowerCase(Locale.ROOT);
        String nome = campos.texto("nome_completo", false, null, CamposDrf.maxLength(120));
        String senha = campos.texto("password", s -> validador.validar(s, email));
        campos.validar();
        long novo = tx.execute(st -> {
            contas.travarConta(email);
            // unico do model e sensivel a caixa; aqui em minusculas: customer existente se promove pelo PATCH
            if (!usuarios.idsPorEmailIgnorandoCaixa(email).isEmpty()) {
                throw new CamposDrf.Invalidos(Map.of("email", List.of("Usuário com este E-mail já existe.")));
            }
            Usuario u = new Usuario();
            u.setEmail(email);
            u.setNomeCompleto(nome == null ? "" : nome);
            u.setPassword(senhas.encode(senha));
            u.setClamaAdmin(true);
            u.setStaff(true);
            u.setDateJoined(OffsetDateTime.now(relogio));
            long id = usuarios.saveAndFlush(u).getId();
            auditoria.registrar(eu.getId(), APP, MODEL, String.valueOf(id), String.valueOf(id),
                    RegistroDeAuditoria.ADICAO, ADICIONADO);
            return id;
        });
        return ResponseEntity.status(201).body(detalhe(String.valueOf(novo)));
    }

    /**
     * Liga/desliga is_clama_admin (com is_staff junto, D7) e is_active. Desativar ou tirar o admin revoga todas as
     * sessoes (D6); promover recusa senha temporaria e ban ativo (D17). Qualquer outra chave do corpo e ignorada.
     */
    @PatchMapping(ITEM)
    Detalhe alterar(@PathVariable String id, @RequestBody(required = false) JsonNode corpo,
            @AuthenticationPrincipal Usuario eu) {
        long n = id(id);
        CamposDrf campos = new CamposDrf(corpo);
        Boolean admin = campos.booleano("is_clama_admin", false);
        Boolean ativo = campos.booleano("is_active", false);
        campos.validar();
        tx.executeWithoutResult(st -> {
            exclusao.travarAdmins();
            Usuario u = usuarios.travar(n).orElseThrow(ClientesAdminController::naoEncontrado);
            if (n == eu.getId()) {
                throw conflito("nao_pode_alterar_a_si_mesmo", "Cannot change own account",
                        "Você não pode mudar o acesso da sua própria conta. Peça a outro superusuário.");
            }
            boolean novoAdmin = admin == null ? u.isClamaAdmin() : admin;
            boolean novoAtivo = ativo == null ? u.isActive() : ativo;
            if (novoAdmin && !u.isClamaAdmin()) {
                if (u.isForceChangePassword()) {
                    throw conflito("senha_temporaria", "Account has a temporary password",
                            "Essa conta ainda usa a senha temporária que foi por e-mail. Defina uma senha nova antes "
                                    + "de torná-la admin.");
                }
                // o mesmo lock do ban (R9 da etapa 6): um ban em curso termina antes desta checagem
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('ban-blog:' || cast(? as text)))",
                        Object.class, n);
                if (Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from blog_customerbanido where "
                        + "customer_id = ? and revogado_em is null)", Boolean.class, n))) {
                    throw conflito("customer_banido", "Customer is banned",
                            "Essa pessoa está banida do blog. Revogue o ban antes de torná-la admin.");
                }
            }
            boolean eraAdminAtivo = u.isClamaAdmin() && u.isActive();
            if (eraAdminAtivo && !(novoAdmin && novoAtivo) && !exclusao.haOutroAdminAtivo(n)) {
                throw conflito("ultimo_admin", "Last active admin",
                        "Essa é a última conta de admin ativa. Crie outra antes.");
            }
            // ordem do changed_data do UserChangeForm (fieldsets); rotulos sem traducao (construct_change_message)
            List<String> mudou = new ArrayList<>();
            if (novoAtivo != u.isActive()) {
                mudou.add("active");
            }
            if (novoAdmin != u.isClamaAdmin()) {
                mudou.add("staff status");
                mudou.add("Admin do Clama");
            }
            if (mudou.isEmpty()) {
                return;
            }
            if ((u.isActive() && !novoAtivo) || (u.isClamaAdmin() && !novoAdmin)) {
                tokens.revogarTodos(n);
            }
            u.setActive(novoAtivo);
            u.setClamaAdmin(novoAdmin);
            u.setStaff(novoAdmin);
            usuarios.flush();
            auditoria.registrar(eu.getId(), APP, MODEL, String.valueOf(n), String.valueOf(n),
                    RegistroDeAuditoria.MUDANCA, List.of(Map.of("changed", Map.of("fields", mudou))));
        });
        return detalhe(id);
    }

    /**
     * AdminPasswordChangeForm sem password2 (a confirmacao e no front), com a similaridade contra o e-mail do alvo.
     * Revoga as sessoes (D6); nao mexe em force_change_password (D5).
     */
    @PostMapping(ITEM + "senha/")
    Map<String, String> definirSenha(@PathVariable String id, @RequestBody(required = false) JsonNode corpo,
            @AuthenticationPrincipal Usuario eu) {
        long n = id(id);
        tx.executeWithoutResult(st -> {
            Usuario u = usuarios.travar(n).orElseThrow(ClientesAdminController::naoEncontrado);
            CamposDrf campos = new CamposDrf(corpo);
            String senha = campos.texto("password", s -> validador.validar(s, u.getEmail()));
            campos.validar();
            u.setPassword(senhas.encode(senha));
            usuarios.flush();
            tokens.revogarTodos(n);
            auditoria.registrar(eu.getId(), APP, MODEL, String.valueOf(n), String.valueOf(n),
                    RegistroDeAuditoria.MUDANCA, SENHA_MUDOU);
        });
        Map<String, String> r = new LinkedHashMap<>();
        r.put("detail", "Senha definida.");
        r.put("pastoral_message", "Senha definida — a pessoa precisa entrar de novo.");
        return r;
    }

    /** D4: ExclusaoDeUsuario (guardas 409 do mapa §4.3), mais a da propria conta. */
    @DeleteMapping(ITEM)
    ResponseEntity<Void> excluir(@PathVariable String id, @AuthenticationPrincipal Usuario eu) {
        long n = id(id);
        if (n == eu.getId()) {
            throw conflito("nao_pode_excluir_a_si_mesmo", "Cannot delete own account",
                    "Você não pode excluir a sua própria conta. Peça a outro superusuário.");
        }
        tx.executeWithoutResult(st -> {
            if (!exclusao.excluir(n)) {
                throw naoEncontrado();
            }
            auditoria.registrar(eu.getId(), APP, MODEL, String.valueOf(n), String.valueOf(n),
                    RegistroDeAuditoria.EXCLUSAO, null);
        });
        return ResponseEntity.noContent().build();
    }

    private static PastoralException conflito(String codigo, String msg, String pastoral) {
        return new PastoralException(409, codigo, msg, pastoral);
    }

    private static PastoralException naoEncontrado() {
        return new PastoralException(404, "not_found", "Customer nao encontrado",
                "Esse customer nao foi encontrado.");
    }
}
