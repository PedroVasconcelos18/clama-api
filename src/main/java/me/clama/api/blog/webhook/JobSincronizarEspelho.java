package me.clama.api.blog.webhook;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.clama.api.blog.PostEspelho;
import me.clama.api.blog.PostEspelhoRepository;
import me.clama.api.blog.ResolucaoDoEspelho;
import me.clama.api.blog.wordpress.StatusWordPress;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.pagamento.webhook.WebhookEvento;
import me.clama.api.pagamento.webhook.WebhookEventoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * sincronizar_post_espelho (blog/tasks.py:306-412): aplica o payload gravado do evento ao espelho, por wp_post_id
 * (update_or_create), e marca o evento PROCESSADO na mesma transacao. Nunca apaga: post_removido vira lixeira.
 *
 * <p>Idempotente, entao retenta (3x, 30 s): o Django declara max_retries mas nunca retenta, e o evento fica RECEBIDO.
 * Esgotadas as retentativas (ex.: data fora do calendario, wp_post_id fora do integer), o evento vai para ERRO.
 */
@Component
public class JobSincronizarEspelho implements TipoDeJob<UUID> {
    private static final Logger LOG = LoggerFactory.getLogger(JobSincronizarEspelho.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String NOME = "sincronizar-espelho";
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo"); // TIME_ZONE do Django (make_aware, R14)
    private static final Set<String> VERDADEIROS = Set.of("1", "true", "yes", "on");
    /** int() do Python sobre texto: espacos, sinal, digitos (Unicode) e "_" entre digitos. */
    private static final Pattern INTEIRO = Pattern.compile("[+-]?\\p{Nd}+(_\\p{Nd}+)*");
    private static final Pattern SO_DATA = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    /** datetime_re do django.utils.dateparse. */
    private static final Pattern DATA_HORA = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})[T ](\\d{1,2}):(\\d{1,2})"
            + "(?::(\\d{1,2})(?:[.,](\\d{1,6})\\d{0,6})?)?\\s*(Z|[+-]\\d{2}(?::?\\d{2})?)?");

    private final JdbcTemplate jdbc;
    private final WebhookEventoRepository eventos;
    private final PostEspelhoRepository espelhos;
    private final TransactionTemplate tx;

    JobSincronizarEspelho(JdbcTemplate jdbc, WebhookEventoRepository eventos, PostEspelhoRepository espelhos,
            TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.eventos = eventos;
        this.espelhos = espelhos;
        this.tx = tx;
    }

    public static String instancia(UUID eventoId) {
        return NOME + "/" + eventoId;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public Class<UUID> payload() {
        return UUID.class;
    }

    @Override
    public Duration esperaEntreTentativas() {
        return Duration.ofSeconds(30); // default_retry_delay=30
    }

    private record Evento(String status, String tipo, JsonNode payload) {}

    @Override
    public void executar(UUID id) {
        List<Evento> achados = jdbc.query("SELECT status, event_type, payload::text FROM payments_webhookevento"
                + " WHERE id = ?", (rs, n) -> new Evento(rs.getString(1), rs.getString(2),
                        rs.getString(3) == null ? JSON.missingNode() : JSON.readTree(rs.getString(3))), id);
        if (achados.isEmpty()) {
            // a linha nao vai passar a existir: sem retentativa
            LOG.warn("sincronizar_post_espelho_evento_ausente (event=sincronizar_post_espelho_skip, "
                    + "webhook_evento_id={})", id);
            return;
        }
        Evento evento = achados.getFirst();
        if (new WebhookEvento(id, evento.status()).terminal()) {
            LOG.info("sincronizar_post_espelho_ja_terminal (event=sincronizar_post_espelho_skip, webhook_evento_id={},"
                    + " status={})", id, evento.status());
            return;
        }
        JsonNode payload = evento.payload();
        BigInteger wpPostId = inteiro(payload.get("wp_post_id"));
        if (wpPostId == null) {
            eventos.marcarStatus(id, WebhookEvento.ERRO);
            LOG.warn("sincronizar_post_espelho_wp_post_id_invalido (event=sincronizar_post_espelho_erro, "
                    + "webhook_evento_id={})", id);
            return;
        }
        String status = evento.tipo().equals("post_removido") ? "lixeira"
                : StatusWordPress.efetivo(TextoPython.strOuVazio(payload.get("status")),
                        comoBool(payload.get("protegido_por_senha")));
        OffsetDateTime publicadoEm = publicadoEm(TextoPython.strOuVazio(payload.get("published_at")));
        // fora do integer (ou negativo, pelo CHECK) o Django falha no banco; aqui tambem falha, e a fila retenta
        int wp = wpPostId.intValueExact();

        boolean criado = Boolean.TRUE.equals(tx.execute(s -> {
            PostEspelho e = espelhos.findByWpPostId(wp).orElse(null);
            boolean novo = e == null;
            if (novo) {
                e = new PostEspelho();
                e.setWpPostId(wp);
            } else {
                e.marcarAlterada(); // save() do Django grava updated_at mesmo sem mudanca
            }
            e.setSlug(ResolucaoDoEspelho.cortar(TextoPython.strOuVazio(payload.get("slug")), 200));
            e.setTitulo(ResolucaoDoEspelho.cortar(TextoPython.strOuVazio(payload.get("titulo")), 200));
            e.setStatus(status);
            e.setPublishedAt(publicadoEm);
            e.setUrl(ResolucaoDoEspelho.cortar(TextoPython.strOuVazio(payload.get("url")), 500));
            espelhos.save(e);
            eventos.marcarStatus(id, WebhookEvento.PROCESSADO);
            return novo;
        }));
        LOG.info("sincronizar_post_espelho_ok (event=sincronizar_post_espelho_ok, wp_post_id={}, criado={}, status={})",
                wp, criado, status);
    }

    /** O evento vai para ERRO (so o status), se ainda nao for terminal: reprocessavel por uma reentrega. */
    @Override
    public void esgotou(UUID id, Throwable erro) {
        eventos.marcarStatus(id, WebhookEvento.ERRO);
    }

    /** int(v) do Python; null onde ele levanta TypeError/ValueError (ausente, None, lista, "12.0"). */
    static BigInteger inteiro(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode() || v.isContainer()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? BigInteger.ONE : BigInteger.ZERO;
        }
        if (v.isNumber()) {
            return v.isIntegralNumber() ? v.bigIntegerValue() : v.decimalValue().toBigInteger(); // 12.7 -> 12
        }
        String s = TextoPython.strip(v.asString());
        return INTEIRO.matcher(s).matches() ? new BigInteger(s.replace("_", "")) : null;
    }

    /** _como_bool: bool fica como esta; o resto, str(v).strip().lower() em {"1", "true", "yes", "on"}. */
    static boolean comoBool(JsonNode v) {
        if (v == null || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        return VERDADEIROS.contains(TextoPython.strip(TextoPython.str(v)).toLowerCase(Locale.ROOT));
    }

    /**
     * parse_datetime do Django 4.2 (fromisoformat, senao o datetime_re), com make_aware no fuso do Django quando
     * ingenua. null: nao e data. Casou o formato com valor fora do calendario: DateTimeException (o ValueError).
     *
     * <p>ponytail: do fromisoformat so a data pura (YYYY-MM-DD) alem do que o datetime_re cobre; hora sem minuto,
     * formato basico (20261119T1200) e semana ISO viram null em vez de data. O WordPress manda "YYYY-MM-DDTHH:MM:SS".
     */
    static OffsetDateTime publicadoEm(String s) {
        Matcher d = SO_DATA.matcher(s);
        if (d.matches()) {
            try {
                return LocalDate.of(num(d, 1), num(d, 2), num(d, 3)).atStartOfDay(FUSO).toOffsetDateTime();
            } catch (DateTimeException foraDoCalendario) {
                return null; // fromisoformat falha e o datetime_re exige hora
            }
        }
        Matcher m = DATA_HORA.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String fracao = m.group(7) == null ? "0" : m.group(7);
        LocalDateTime t = LocalDateTime.of(num(m, 1), num(m, 2), num(m, 3), num(m, 4), num(m, 5),
                m.group(6) == null ? 0 : num(m, 6), Integer.parseInt((fracao + "00000").substring(0, 6)) * 1000);
        String tz = m.group(8);
        if (tz == null) {
            return t.atZone(FUSO).toOffsetDateTime();
        }
        if (tz.equals("Z")) {
            return t.atOffset(ZoneOffset.UTC);
        }
        int minutos = Integer.parseInt(tz.substring(1, 3)) * 60 + (tz.length() > 3
                ? Integer.parseInt(tz.substring(tz.length() - 2)) : 0);
        return t.atOffset(ZoneOffset.ofTotalSeconds((tz.charAt(0) == '-' ? -minutos : minutos) * 60));
    }

    /** O grupo como int; ano 0 levanta como no datetime do Python (MINYEAR = 1), o java.time o aceitaria. */
    private static int num(Matcher m, int grupo) {
        int n = Integer.parseInt(m.group(grupo));
        if (grupo == 1 && n < 1) {
            throw new DateTimeException("year 0 is out of range");
        }
        return n;
    }
}
