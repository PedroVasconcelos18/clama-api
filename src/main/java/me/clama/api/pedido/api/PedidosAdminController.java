package me.clama.api.pedido.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.auditoria.RegistroDeAuditoria;
import me.clama.api.comum.email.ValidadorDeEmail;
import me.clama.api.identidade.Usuario;
import me.clama.api.oracao.AlertaDeErro;
import me.clama.api.pedido.CanalEntrega;
import me.clama.api.pedido.StatusPedido;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.JsonNode;
import me.clama.api.comum.Dinheiro;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.PaginaLimitOffset;
import me.clama.api.comum.api.ParametroInvalido;
import me.clama.api.comum.api.ParametrosDeQuery;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminPedidoListView e AdminPedidoDetailView (admin_views.py): acesso admin e do SegurancaConfig (/api/admin/**).
 * Filtros em SQL puro (JdbcTemplate); os itens vem da entidade, que decifra a PII. Filtro malformado: o Django da 500,
 * aqui 400 {"detail": "Parâmetro <nome> inválido."}.
 */
@RestController
class PedidosAdminController {
    private static final Pattern UUID_HEX = Pattern.compile("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern INTEIRO = Pattern.compile("[+-]?[0-9]+");
    private static final Map<String, String> ORDENACOES = Map.of("created_at", "created_at", "-created_at",
            "created_at desc", "valor_centavos", "valor_centavos", "-valor_centavos", "valor_centavos desc", "status",
            "status", "-status", "status desc");

    private final PedidoRepository pedidos;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlanoRepository planos;
    private final RegistroDeAuditoria auditoria;
    private final AlertaDeErro alerta;

    PedidosAdminController(PedidoRepository pedidos, JdbcTemplate jdbc, TransactionTemplate tx, PlanoRepository planos,
            RegistroDeAuditoria auditoria, AlertaDeErro alerta) {
        this.pedidos = pedidos;
        this.jdbc = jdbc;
        this.tx = tx;
        this.planos = planos;
        this.auditoria = auditoria;
        this.alerta = alerta;
    }

    /** AdminPedidoListSerializer. */
    record Item(UUID id, String created_at, String nome, String email, String telefone, String plano_nome,
            int valor_centavos, String valor_reais_str, String status, String canal_entrega, boolean eh_gratuito) {
        static Item de(Pedido p) {
            return new Item(p.getId(), DatasDrf.dataHora(p.getCreatedAt()), p.getNome(), p.getEmail(), p.getTelefone(),
                    p.getPlano().getNome(), p.getValorCentavos(), p.valorReaisStr(), p.getStatus().valor(),
                    p.getCanalEntrega().valor(), p.isEhGratuito());
        }
    }

    record Plano(UUID id, String nome, String valor_reais_str) {}

    record Instituicao(UUID id, String nome, String logo) {}

    record Webhook(UUID id, String provider, String external_event_id, String event_type, String status,
            String error_message, String created_at) {}

    /** AdminPedidoDetailSerializer (sem payload dos webhooks, sem cpf/pix/consent/device_hash/user). */
    record Detalhe(UUID id, String created_at, String updated_at, String nome, String email, String telefone,
            Integer idade, String sexo, String pedido_oracao, String oracao_gerada, Plano plano,
            Instituicao instituicao, int valor_centavos, String valor_reais_str, boolean eh_gratuito, String status,
            String canal_entrega, String provider_payment_id, String provider_checkout_url,
            String whatsapp_message_id, String whatsapp_delivered_at, String whatsapp_read_at, int retry_count,
            String last_error, List<Webhook> webhook_events) {}

    @GetMapping("/api/admin/pedidos/")
    Map<String, Object> listar(HttpServletRequest req) {
        PaginaLimitOffset pagina = PaginaLimitOffset.de(req);
        List<String> onde = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        String status = ParametrosDeQuery.ultimo(req, "status");
        if (status != null && !status.isEmpty()) {
            List<String> valores = new ArrayList<>();
            for (String s : status.split(",", -1)) {
                valores.add(TextoPython.strip(s).toLowerCase(Locale.ROOT));
            }
            onde.add("status = any(?)");
            args.add(valores.toArray(String[]::new));
        }
        String canal = ParametrosDeQuery.ultimo(req, "canal");
        if (canal != null && !canal.isEmpty()) {
            onde.add("canal_entrega = ?");
            args.add(canal.toLowerCase(Locale.ROOT));
        }
        String plano = ParametrosDeQuery.ultimo(req, "plano");
        if (plano != null && !plano.isEmpty()) {
            if (!UUID_HEX.matcher(plano).matches()) {
                throw new ParametroInvalido("plano");
            }
            onde.add("plano_id = ?::uuid");
            args.add(plano);
        }
        dia(req, "created_after", ">=", onde, args);
        dia(req, "created_before", "<=", onde, args);
        String userId = ParametrosDeQuery.ultimo(req, "user_id");
        if (userId != null && !userId.isEmpty()) {
            String u = TextoPython.strip(userId);
            if (!INTEIRO.matcher(u).matches()) {
                throw new ParametroInvalido("user_id");
            }
            onde.add("user_id = ?::bigint");
            args.add(u.startsWith("+") ? u.substring(1) : u);
        }
        String q = ParametrosDeQuery.ultimo(req, "q");
        if (q != null && !q.isEmpty()) {
            // D1 (Etapa 7): o Django busca com ILIKE no texto cifrado e nunca acha nada. Aqui: conta (e-mail/nome),
            // prefixo do id e, para pedido sem conta, nome/e-mail decifrados em memoria.
            String escapado = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String contem = "%" + escapado + "%";
            String agulha = q.toLowerCase(Locale.ROOT);
            // ponytail: varre todos os pedidos sem conta a cada busca (O(n) decifrando); produção tem poucos. Se
            // crescer, coluna de hash (HMAC do e-mail normalizado) em vez de decifrar.
            String[] semConta = pedidos.semUsuario().stream()
                    .filter(l -> contemTexto(l[1], agulha) || contemTexto(l[2], agulha)).map(l -> l[0].toString())
                    .toArray(String[]::new);
            onde.add("(user_id in (select id from users_user where email ilike ? or nome_completo ilike ?)"
                    + " or id::text like ? or id::text = any(?))");
            args.add(contem);
            args.add(contem);
            args.add(escapado.toLowerCase(Locale.ROOT) + "%");
            args.add(semConta);
        }
        String ordem = ORDENACOES.getOrDefault(Objects.toString(ParametrosDeQuery.ultimo(req, "ordering"), ""),
                "created_at desc");
        String filtro = onde.isEmpty() ? "" : " where " + String.join(" and ", onde);

        return tx.execute(st -> {
            Long count = jdbc.queryForObject("select count(*) from orders_pedido" + filtro, Long.class, args.toArray());
            List<Object> argsPagina = new ArrayList<>(args);
            argsPagina.add(pagina.limit());
            argsPagina.add(pagina.offset());
            List<UUID> ids = jdbc.queryForList("select id from orders_pedido" + filtro + " order by " + ordem
                    + ", created_at desc, id limit ? offset ?", UUID.class, argsPagina.toArray());
            Map<UUID, Pedido> porId = new java.util.HashMap<>();
            if (!ids.isEmpty()) {
                pedidos.comPlano(ids).forEach(p -> porId.put(p.getId(), p));
            }
            return pagina.resposta(count, ids.stream().map(porId::get).map(Item::de).toList());
        });
    }

    private static boolean contemTexto(Object valor, String agulhaMinuscula) {
        return valor != null && valor.toString().toLowerCase(Locale.ROOT).contains(agulhaMinuscula);
    }

    /** created_at__date no fuso America/Sao_Paulo; data invalida e 400. */
    private static void dia(HttpServletRequest req, String nome, String op, List<String> onde, List<Object> args) {
        String v = ParametrosDeQuery.ultimo(req, nome);
        if (v == null || v.isEmpty()) {
            return;
        }
        try {
            args.add(java.sql.Date.valueOf(LocalDate.parse(v)));
        } catch (DateTimeParseException e) {
            throw new ParametroInvalido(nome);
        }
        onde.add("(created_at at time zone 'America/Sao_Paulo')::date " + op + " ?");
    }

    private static final String ITEM = "/api/admin/pedidos/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/";

    private static PastoralException naoEncontrado() {
        return new PastoralException(404, "not_found", "Pedido não encontrado",
                "Esse pedido não foi encontrado. Pode ter sido removido?");
    }

    /**
     * Edicao do PedidoAdmin (admin.py): nome, e-mail, telefone, idade, sexo, pedido, oracao, plano, valor, canal e
     * status, este livre (sem maquina de estados, como o ModelForm). Nenhum job e enfileirado. Linha travada; auditoria
     * com os rotulos do Django na ordem do formulario ([] quando nada mudou); status erro avisa o admin em QUALQUER
     * save (o post_save do Django), com a trava de 1 h dentro do AlertaDeErro.
     */
    @PatchMapping(ITEM)
    Detalhe alterar(@PathVariable UUID id, @RequestBody(required = false) JsonNode corpo,
            @AuthenticationPrincipal Usuario eu) {
        tx.executeWithoutResult(st -> {
            Pedido p = pedidos.travar(id).orElseThrow(PedidosAdminController::naoEncontrado);
            CamposDrf c = new CamposDrf(corpo);
            String nome = c.texto("nome", false, CamposDrf.BRANCO, CamposDrf.maxLength(120));
            String email = c.texto("email", false, CamposDrf.BRANCO, CamposDrf.maxLength(254),
                    s -> ValidadorDeEmail.valido(s) ? List.of() : List.of("Informe um endereço de email válido."));
            String telefone = c.texto("telefone", false, null, CamposDrf.maxLength(20));
            JsonNode idadeJson = c.get("idade");
            boolean idadeNula = idadeJson != null && idadeJson.isNull();
            Integer idade = idadeNula ? null : c.inteiro("idade", false, 0, Short.MAX_VALUE);
            String sexo = c.escolha("sexo", false, List.of("", "feminino", "masculino", "nao_informado"));
            String pedidoOracao = c.textoEmBranco("pedido_oracao");
            String oracaoGerada = c.textoEmBranco("oracao_gerada");
            var plano = plano(c);
            Integer valor = c.inteiro("valor_centavos", false, 0, Integer.MAX_VALUE);
            String canal = c.escolha("canal_entrega", false, List.of("email", "whatsapp"));
            String status = c.escolha("status", false, java.util.Arrays.stream(StatusPedido.values())
                    .map(StatusPedido::valor).toList());
            c.validar();

            // ordem e rotulos do formulario do PedidoAdmin (verbose_name do model)
            List<String> mudou = new ArrayList<>();
            if (nome != null && !nome.equals(p.getNome())) {
                p.setNome(nome);
                mudou.add("Nome");
            }
            if (email != null && !email.equals(p.getEmail())) {
                p.setEmail(email);
                mudou.add("E-mail");
            }
            if (telefone != null && !telefone.equals(p.getTelefone())) {
                p.setTelefone(telefone);
                mudou.add("Telefone");
            }
            if ((idadeNula || idade != null) && !Objects.equals(idade, p.getIdade())) {
                p.setIdade(idade);
                mudou.add("Idade");
            }
            if (sexo != null && !sexo.equals(p.getSexo())) {
                p.setSexo(sexo);
                mudou.add("Sexo");
            }
            if (pedidoOracao != null && !pedidoOracao.equals(p.getPedidoOracao())) {
                p.setPedidoOracao(pedidoOracao);
                mudou.add("Pedido de oração");
            }
            if (oracaoGerada != null && !oracaoGerada.equals(p.getOracaoGerada())) {
                p.setOracaoGerada(oracaoGerada);
                mudou.add("Oração gerada");
            }
            if (plano != null && !plano.getId().equals(p.getPlano().getId())) {
                p.setPlano(plano);
                mudou.add("Plano");
            }
            if (valor != null && valor != p.getValorCentavos()) {
                p.setValorCentavos(valor);
                mudou.add("Valor (centavos)");
            }
            if (canal != null && !canal.equals(p.getCanalEntrega().valor())) {
                p.setCanalEntrega("whatsapp".equals(canal) ? CanalEntrega.WHATSAPP : CanalEntrega.EMAIL);
                mudou.add("Canal de entrega");
            }
            if (status != null && !status.equals(p.getStatus().valor())) {
                p.setStatus(StatusPedido.de(status));
                mudou.add("Status");
            }
            pedidos.flush();
            auditoria.registrar(eu.getId(), "orders", "pedido", id.toString(), id.toString(),
                    RegistroDeAuditoria.MUDANCA,
                    mudou.isEmpty() ? List.of() : List.of(Map.of("changed", Map.of("fields", mudou))));
            if (p.getStatus() == StatusPedido.ERRO) {
                alerta.registrar(id);
            }
        });
        return detalhe(id);
    }

    /** ModelChoiceField(Plan.objects.all()): qualquer plano, ativo ou nao; "" e null sao invalidos (campo obrigatorio). */
    private me.clama.api.catalogo.plano.Plano plano(CamposDrf c) {
        JsonNode v = c.get("plano");
        if (v == null) {
            return null;
        }
        if (v.isNull()) {
            c.erro("plano", CamposDrf.NULO);
            return null;
        }
        String s = v.isString() ? TextoPython.strip(v.asString()) : "";
        if (s.isEmpty()) {
            c.erro("plano", v.isString() ? CamposDrf.BRANCO : "Tipo incorreto. Esperava valor pk, recebeu "
                    + CamposDrf.tipoPython(v) + ".");
            return null;
        }
        if (!UUID_HEX.matcher(s).matches()) {
            c.erro("plano", "O valor “" + s + "” não é um UUID válido");
            return null;
        }
        var plano = planos.findById(UUID.fromString(s.length() == 32 ? s.replaceFirst(
                "(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5") : s)).orElse(null);
        if (plano == null) {
            c.erro("plano", "Pk inválido \"" + s + "\" - objeto não existe.");
        }
        return plano;
    }

    @GetMapping(ITEM)
    Detalhe detalhe(@PathVariable UUID id) {
        return tx.execute(st -> {
            Pedido p = pedidos.detalhe(id).orElseThrow(PedidosAdminController::naoEncontrado);
            var i = p.getInstituicao();
            // WebhookEvento so mapeia id e status: o resto vem em SQL puro, por pedido_id e sem o payload
            List<Webhook> eventos = jdbc.query("""
                    select id, provider, external_event_id, event_type, status, error_message, created_at
                    from payments_webhookevento where pedido_id = ? order by created_at desc""",
                    (rs, n) -> new Webhook(rs.getObject("id", UUID.class), rs.getString("provider"),
                            rs.getString("external_event_id"), rs.getString("event_type"), rs.getString("status"),
                            rs.getString("error_message"),
                            DatasDrf.dataHora(rs.getObject("created_at", java.time.OffsetDateTime.class))), id);
            return new Detalhe(p.getId(), DatasDrf.dataHora(p.getCreatedAt()), DatasDrf.dataHora(p.getUpdatedAt()),
                    p.getNome(), p.getEmail(), p.getTelefone(), p.getIdade(), p.getSexo(), p.getPedidoOracao(),
                    p.getOracaoGerada(),
                    new Plano(p.getPlano().getId(), p.getPlano().getNome(),
                            Dinheiro.centavosParaReaisStr(p.getPlano().getValorCentavos())),
                    i == null ? null : new Instituicao(i.getId(), i.getNome(), i.getLogo()), p.getValorCentavos(),
                    p.valorReaisStr(), p.isEhGratuito(), p.getStatus().valor(), p.getCanalEntrega().valor(),
                    p.getProviderPaymentId(), p.getProviderCheckoutUrl(), p.getWhatsappMessageId(),
                    DatasDrf.dataHora(p.getWhatsappDeliveredAt()), DatasDrf.dataHora(p.getWhatsappReadAt()),
                    p.getRetryCount(), p.getLastError(), eventos);
        });
    }
}
