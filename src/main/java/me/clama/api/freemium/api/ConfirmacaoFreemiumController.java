package me.clama.api.freemium.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import me.clama.api.comum.api.ParametrosDeQuery;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.email.modelo.ModelosDeEmail;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.conta.GeradorDeSenhaTemporaria;
import me.clama.api.conta.ProvisionamentoDeConta;
import me.clama.api.freemium.FreemiumBlacklist;
import me.clama.api.freemium.FreemiumBlacklistRepository;
import me.clama.api.freemium.TokenDeConfirmacao;
import me.clama.api.freemium.TokenDeConfirmacaoRepository;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.oracao.JobEnviarOracao;
import me.clama.api.oracao.JobGerarOracao;
import me.clama.api.oracao.SenhasTemporarias;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * FreemiumConfirmarView (freemium/api/views.py:436-750). GET so redireciona ao front, sem tocar no token. POST e a saga
 * numa transacao so, na ordem do Django: token e pedido travados, estado, blacklist, conta (sob o lock conta-doador,
 * decisao R2), linha da blacklist, senha temporaria (apagada no rollback, R5), pedido em gerando_oracao, token usado
 * e gerar-oracao enfileirado (R6). Qualquer erro desfaz tudo e o token segue usavel. Throttle num balde so para GET e
 * POST. 302 e 200 levam no-referrer e no-store; os erros, o Referrer-Policy global e nenhum Cache-Control.
 */
@RestController
class ConfirmacaoFreemiumController {
    private static final Logger LOG = LoggerFactory.getLogger(ConfirmacaoFreemiumController.class);
    private static final String ROTA = "/api/freemium/confirmar/";
    /** safe do quote do iri_to_uri, alem de letras, digitos e "_.-~". */
    private static final String IRI_SEGUROS = "/#%[]=:;$&()+,!?*@'~_.-";

    private final TokenDeConfirmacaoRepository tokens;
    private final FreemiumBlacklistRepository blacklist;
    private final UsuarioRepository usuarios;
    private final ProvisionamentoDeConta provisionamento;
    private final HashIdentificador hash;
    private final SenhaDjango senhaDjango;
    private final SenhasTemporarias senhas;
    private final Fila fila;
    private final JobGerarOracao gerarOracao;
    private final ModelosDeEmail modelos;
    private final TransactionTemplate tx;
    private final Clock relogio;

    ConfirmacaoFreemiumController(TokenDeConfirmacaoRepository tokens, FreemiumBlacklistRepository blacklist,
            UsuarioRepository usuarios, ProvisionamentoDeConta provisionamento, HashIdentificador hash,
            SenhaDjango senhaDjango, SenhasTemporarias senhas, Fila fila, JobGerarOracao gerarOracao,
            ModelosDeEmail modelos, TransactionTemplate tx, Clock relogio) {
        this.tokens = tokens;
        this.blacklist = blacklist;
        this.usuarios = usuarios;
        this.provisionamento = provisionamento;
        this.hash = hash;
        this.senhaDjango = senhaDjango;
        this.senhas = senhas;
        this.fila = fila;
        this.gerarOracao = gerarOracao;
        this.modelos = modelos;
        this.tx = tx;
        this.relogio = relogio;
    }

    /** Sempre 302, nunca JSON. Location montado a mao: o Django so aplica iri_to_uri (o & e o # do token ficam). */
    @GetMapping(ROTA)
    @LimiteDeTaxa(escopo = "freemium_confirmar_ip", limite = 30, janelaSegundos = 3600)
    ResponseEntity<Void> redirecionar(HttpServletRequest req) {
        String token = TextoPython.strip(nulo(ParametrosDeQuery.ultimo(req, "token")));
        String destino = modelos.frontendBase() + "/confirmar" + (token.isEmpty() ? "" : "?token=" + token);
        return ResponseEntity.status(302).header("Location", iriParaUri(destino)).header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store").build();
    }

    @PostMapping(ROTA)
    @LimiteDeTaxa(escopo = "freemium_confirmar_ip", limite = 30, janelaSegundos = 3600)
    ResponseEntity<Confirmado> confirmar(@RequestBody(required = false) JsonNode corpo, HttpServletRequest req) {
        // (data.get("token") or query_params.get("token") or "").strip(); body nao-objeto e token nao-string: 400 (R14)
        if (corpo != null && !corpo.isNull() && !corpo.isObject()) throw invalido();
        JsonNode t = corpo == null ? null : corpo.get("token");
        if (t != null && !t.isNull() && !t.isString()) throw invalido();
        String doCorpo = t == null || t.isNull() ? "" : t.asString();
        String token = TextoPython.strip(doCorpo.isEmpty() ? nulo(ParametrosDeQuery.ultimo(req, "token")) : doCorpo);

        Confirmado c = tx.execute(s -> saga(token));
        return ResponseEntity.ok().header("Referrer-Policy", "no-referrer").header("Cache-Control", "no-store").body(c);
    }

    private Confirmado saga(String token) {
        if (token.isEmpty()) throw invalido();
        String prefixo = token.substring(0, Math.min(8, token.length()));
        // primeiro na transacao: trava token e pedido antes de qualquer leitura deles (senao viria entidade velha)
        TokenDeConfirmacao t = tokens.travarComPedido(token).orElse(null);
        if (t == null) {
            LOG.info("Tentativa de uso de token inexistente (event=freemium_confirmation_token_inexistente,"
                    + " token_prefixo={})", prefixo);
            throw invalido();
        }
        Pedido p = t.getPedido();
        if (t.getUsedAt() != null) {
            LOG.info("Tentativa de reuso de token já consumido (event=freemium_confirmation_token_ja_usado,"
                    + " pedido_id={}, token_prefixo={})", p.getId(), prefixo);
            throw invalido();
        }
        OffsetDateTime agora = OffsetDateTime.now(relogio);
        if (!t.getExpiresAt().isAfter(agora)) {
            LOG.info("Tentativa de uso de token expirado (event=freemium_confirmation_token_expirado, pedido_id={},"
                    + " token_prefixo={})", p.getId(), prefixo);
            throw new PastoralException(400, "confirmation_token_expirado", "Token de confirmação expirado",
                    "Esse link expirou. Faça um novo pedido pra receber sua oração grátis.");
        }
        if (p.getStatus() != StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL) {
            LOG.info("Confirmação freemium em pedido fora do estado esperado (event=freemium_confirm_estado_invalido,"
                    + " pedido_id={}, status_atual={})", p.getId(), p.getStatus().valor());
            throw invalido();
        }

        String cpfHash = hash.hashCpfCnpj(p.getCpfCnpj());
        String emailHash = hash.hashEmail(p.getEmail());
        String telefoneHash = hash.hashTelefone(p.getTelefone());
        if (blacklist.existeAlgum(cpfHash, emailHash, telefoneHash, p.getDeviceHash())) {
            LOG.info("Blacklist hit detectado durante confirmação freemium (event=freemium_blacklist_hit_confirm,"
                    + " pedido_id={})", p.getId());
            throw PedidoFreemiumController.blacklistHit();
        }

        // R2: o lock da conta antes de criar; e-mail literal ja existente = o IntegrityError do Django (409)
        provisionamento.travarConta(p.getEmail());
        String senha = GeradorDeSenhaTemporaria.gerar();
        Usuario u = new Usuario();
        u.setEmail(p.getEmail());
        u.setPassword(senhaDjango.encode(senha));
        u.setNomeCompleto(p.getNome() == null ? "" : p.getNome());
        u.setCpfCnpj(p.getCpfCnpj());
        u.setTelefone(p.getTelefone());
        u.setIdade(p.getIdade());
        u.setSexo(p.getSexo());
        u.setForceChangePassword(true);
        u.setFreemiumUsedAt(agora);
        if (usuarios.existsByEmail(u.getEmail())) throw colisaoDeEmail(p);
        try {
            usuarios.saveAndFlush(u);
        } catch (DataIntegrityViolationException e) {
            throw colisaoDeEmail(p);
        }

        FreemiumBlacklist b = new FreemiumBlacklist();
        b.setCpfHash(cpfHash);
        b.setEmailHash(emailHash);
        b.setTelefoneHash(telefoneHash);
        b.setDeviceHash(p.getDeviceHash() == null || p.getDeviceHash().isEmpty() ? null : p.getDeviceHash());
        b.setIpHash(p.getConsentIp() == null ? null : hash.hashIp(p.getConsentIp()));
        b.setCreatedAt(agora);
        try {
            blacklist.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            LOG.info("Blacklist insert falhou durante confirmação (race)"
                    + " (event=freemium_blacklist_unique_collision_confirm, pedido_id={})", p.getId());
            throw PedidoFreemiumController.blacklistHit();
        }

        senhas.guardarNaTransacao(JobEnviarOracao.PREFIXO_SENHA_FREEMIUM + u.getId(), senha);
        // status, updated_at e o gerar-oracao na mesma transacao (invariante da varredura de pedidos presos)
        p.setUser(u);
        p.setStatus(StatusPedido.GERANDO_ORACAO);
        t.setUsedAt(agora);
        fila.enfileirar(gerarOracao, JobGerarOracao.instancia(p.getId()), p.getId());
        LOG.info("Token de confirmação freemium consumido (event=freemium_confirmation_token_consumido, pedido_id={},"
                + " token_prefixo={})", p.getId(), prefixo);
        return new Confirmado(p.getId(), StatusPedido.GERANDO_ORACAO.valor());
    }

    /** FreemiumConfirmarResponseSerializer, nessa ordem de chaves. */
    record Confirmado(UUID pedido_id, String status) {}

    private static PastoralException invalido() {
        return new PastoralException(400, "confirmation_token_invalido", "Token de confirmação inválido ou já utilizado",
                "Esse link não é mais válido. Faça um novo pedido.");
    }

    /** R10: sem o texto da violacao (traz o e-mail). */
    private static PastoralException colisaoDeEmail(Pedido p) {
        LOG.info("Confirmação freemium colide com User existente (event=freemium_user_email_collision, pedido_id={})",
                p.getId());
        return PedidoFreemiumController.blacklistHit();
    }

    private static String nulo(String v) {
        return v == null ? "" : v;
    }

    /** django.utils.encoding.iri_to_uri: quote(iri, safe="/#%[]=:;$&()+,!?*@'~") sobre o UTF-8. */
    static String iriParaUri(String iri) {
        StringBuilder s = new StringBuilder();
        for (byte b : iri.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && (Character.isLetterOrDigit(c) || IRI_SEGUROS.indexOf(c) >= 0)) {
                s.append((char) c);
            } else {
                s.append('%').append(String.format(java.util.Locale.ROOT, "%02X", c));
            }
        }
        return s.toString();
    }
}
