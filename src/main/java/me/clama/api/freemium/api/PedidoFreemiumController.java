package me.clama.api.freemium.api;

import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.catalogo.plano.PlanoGratuito;
import me.clama.api.comum.antibot.Turnstile;
import me.clama.api.comum.antibot.TurnstileIndisponivel;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.email.EmailDescartavel;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.fila.Fila;
import me.clama.api.comum.ratelimit.ClienteIp;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.freemium.FreemiumBlacklistRepository;
import me.clama.api.freemium.JobConfirmacaoFreemium;
import me.clama.api.freemium.TokenDeConfirmacao;
import me.clama.api.freemium.TokenDeConfirmacaoRepository;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.pedido.CanalEntrega;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * PedidoFreemiumCreateView (freemium/api/views.py:186-430), na ordem do Django: campos, Turnstile, descartavel, conta
 * existente, pedido pendente, blacklist permanente, blacklist por IP, plano. So entao Pedido + token + job numa
 * transacao: falha ao enfileirar desfaz tudo e da 500 (decisao R6; o Django logava e respondia 201).
 */
@RestController
class PedidoFreemiumController {
    private static final Logger LOG = LoggerFactory.getLogger(PedidoFreemiumController.class);
    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final Duration TTL_TOKEN = Duration.ofHours(24);

    private final Turnstile turnstile;
    private final HashIdentificador hash;
    private final UsuarioRepository usuarios;
    private final PedidoRepository pedidos;
    private final FreemiumBlacklistRepository blacklist;
    private final TokenDeConfirmacaoRepository tokens;
    private final PlanoGratuito planoGratuito;
    private final Fila fila;
    private final JobConfirmacaoFreemium job;
    private final TransactionTemplate tx;
    private final Clock relogio;
    private final int limiarIp;
    private final int janelaIpHoras;

    PedidoFreemiumController(Turnstile turnstile, HashIdentificador hash, UsuarioRepository usuarios,
            PedidoRepository pedidos, FreemiumBlacklistRepository blacklist, TokenDeConfirmacaoRepository tokens,
            PlanoGratuito planoGratuito, Fila fila, JobConfirmacaoFreemium job, TransactionTemplate tx, Clock relogio,
            @Value("${FREEMIUM_IP_BLACKLIST_THRESHOLD:3}") int limiarIp,
            @Value("${FREEMIUM_IP_BLACKLIST_WINDOW_HOURS:1}") int janelaIpHoras) {
        this.turnstile = turnstile;
        this.hash = hash;
        this.usuarios = usuarios;
        this.pedidos = pedidos;
        this.blacklist = blacklist;
        this.tokens = tokens;
        this.planoGratuito = planoGratuito;
        this.fila = fila;
        this.job = job;
        this.tx = tx;
        this.relogio = relogio;
        this.limiarIp = limiarIp;
        this.janelaIpHoras = janelaIpHoras;
    }

    @PostMapping("/api/freemium/pedidos/")
    @ResponseStatus(HttpStatus.CREATED)
    @LimiteDeTaxa(escopo = "freemium_pedido_ip", limite = 5, janelaSegundos = 3600)
    Criado criar(@RequestBody(required = false) JsonNode corpo, HttpServletRequest req) {
        PedidoFreemium d = FormularioFreemium.validar(corpo);
        String ip = ClienteIp.validado(req);

        // Turnstile fora de transacao: nao segura conexao durante a chamada de rede
        boolean humano;
        try {
            humano = turnstile.validar(d.turnstileToken(), ip);
        } catch (TurnstileIndisponivel e) {
            Sentry.captureException(e);
            LOG.error("Falha permanente ao validar Turnstile event=turnstile_falha_permanente", e);
            humano = false;
        }
        if (!humano) {
            throw new PastoralException(400, "captcha_invalido", "Token Turnstile inválido",
                    "Verificação anti-robô falhou. Atualize a página e tente de novo.");
        }
        if (EmailDescartavel.eh(d.email())) {
            throw new PastoralException(400, "email_descartavel", "E-mail descartável não permitido",
                    "Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.");
        }

        String cpfHash = hash.hashCpfCnpj(d.cpfCnpj());
        String emailHash = hash.hashEmail(d.email());
        String telefoneHash = hash.hashTelefone(d.telefone());
        if (usuarios.existeConta(d.email(), emailHash, cpfHash, telefoneHash)) {
            LOG.info("Submissão freemium bloqueada — user já tem conta (gate) event=freemium_user_existente_gate_hit");
            throw new PastoralException(409, "user_ja_possui_conta", "User já tem conta — redirect para /login",
                    "Você já tem conta com a gente. Faça login pra fazer um novo pedido.", Map.of("redirect", "/login"));
        }

        // os identificadores do Pedido sao cifrados: decifra cada pendente e compara os hashes (sem filtro de idade, R1)
        // ponytail: varre todos os pendentes a cada submit, como o Django; se crescer, coluna de hash no pedido
        for (Pedido p : pedidos.findByEhGratuitoTrueAndStatus(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL)) {
            if (hash.hashCpfCnpj(p.getCpfCnpj()).equals(cpfHash) || hash.hashEmail(p.getEmail()).equals(emailHash)
                    || hash.hashTelefone(p.getTelefone()).equals(telefoneHash)) {
                LOG.info("Submissão freemium bloqueada — pedido pendente do mesmo identificador"
                        + " event=freemium_pedido_em_andamento_gate_hit pedido_id={}", p.getId());
                throw new PastoralException(409, "pedido_em_andamento",
                        "Pedido pendente aguardando confirmação por e-mail",
                        "Você já tem um pedido aguardando confirmação. Confira seu e-mail para finalizar — se não"
                                + " achar, olha na aba de spam.");
            }
        }

        if (blacklist.existeAlgum(cpfHash, emailHash, telefoneHash, d.deviceHash())) {
            LOG.info("Tentativa de submissão freemium com identificador já na blacklist"
                    + " event=freemium_blacklist_hit_submit layer=permanent");
            throw blacklistHit();
        }
        if (ip != null) {
            OffsetDateTime desde = OffsetDateTime.now(relogio).minusHours(janelaIpHoras);
            long n = blacklist.contarPorIpDesde(hash.hashIp(ip), desde);
            if (n >= limiarIp) {
                LOG.info("Submissão freemium bloqueada — IP atingiu threshold event=freemium_blacklist_hit_submit"
                        + " layer=ip_threshold ip_count_in_window={} threshold={} window_hours={}", n, limiarIp,
                        janelaIpHoras);
                throw blacklistHit();
            }
        }

        Plano plano = planoGratuito.obter();
        return tx.execute(st -> {
            OffsetDateTime agora = OffsetDateTime.now(relogio);
            Pedido p = new Pedido();
            p.setNome(d.nome());
            p.setEmail(d.email());
            p.setTelefone(d.telefone());
            p.setCpfCnpj(d.cpfCnpj());
            p.setIdade(d.idade());
            p.setSexo(d.sexo());
            p.setPedidoOracao(d.pedidoOracao());
            p.setPlano(plano);
            p.setValorCentavos(0);
            p.setEhGratuito(true);
            p.setCanalEntrega(CanalEntrega.EMAIL);
            p.setStatus(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
            p.setConsentAceito(true);
            p.setConsentVersao("1"); // POLITICA_VERSAO_ATUAL (clama/core/legal.py)
            p.setConsentAceitoAt(agora);
            p.setConsentIp(ip);
            p.setDeviceHash(d.deviceHash());
            pedidos.saveAndFlush(p);

            TokenDeConfirmacao t = new TokenDeConfirmacao();
            t.setToken(novoToken());
            t.setPedido(p);
            t.setExpiresAt(agora.plus(TTL_TOKEN));
            t.setIpOrigem(ip);
            t.setDeviceHash(d.deviceHash());
            tokens.saveAndFlush(t);
            LOG.info("Token de confirmação freemium gerado event=freemium_confirmation_token_gerado pedido_id={}"
                    + " token_prefixo={}", p.getId(), t.getToken().substring(0, 8));

            fila.enfileirar(job, JobConfirmacaoFreemium.instancia(p.getId()), p.getId());
            return new Criado(p.getId(), p.getStatus().valor());
        });
    }

    /** PedidoFreemiumCreateResponseSerializer, nessa ordem de chaves. */
    record Criado(UUID pedido_id, String status) {}

    /** secrets.token_urlsafe(48): 48 bytes em Base64 URL sem padding = 64 caracteres [A-Za-z0-9_-]. */
    static String novoToken() {
        byte[] b = new byte[48];
        ALEATORIO.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static PastoralException blacklistHit() {
        return new PastoralException(409, "freemium_blacklist_hit", "Identificador já usou o pedido gratuito",
                "Você já recebeu sua oração gratuita. Sempre que quiser, pode fazer um novo pedido pela página"
                        + " principal.");
    }
}
