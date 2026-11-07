package me.clama.api.pedido.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.Dinheiro;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * OverviewMetricsView e DistributionMetricsView (metrics_views.py): acesso admin e do SegurancaConfig. Janelas em UTC.
 * Sem cache no servidor: so os headers do cache_page(60), e so no 200.
 */
@RestController
class MetricasAdminController {
    private static final DateTimeFormatter SEGUNDOS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    private static final List<StatusPedido> ALERTAS = List.of(StatusPedido.ERRO, StatusPedido.AGUARDANDO_REENVIO);

    private final JdbcTemplate jdbc;
    private final PedidoRepository pedidos;
    private final Clock relogio;

    MetricasAdminController(JdbcTemplate jdbc, PedidoRepository pedidos, Clock relogio) {
        this.jdbc = jdbc;
        this.pedidos = pedidos;
        this.relogio = relogio;
    }

    /** start/end como o isoformat() do Python (micro so se houver) e os limites como instante. */
    record Janela(String start, String end, OffsetDateTime de, OffsetDateTime ate) {}

    /** _get_period_range; null se o periodo nao for day, week ou month. */
    static Janela janela(String period, Instant agora) {
        OffsetDateTime now = agora.atOffset(ZoneOffset.UTC);
        OffsetDateTime de;
        OffsetDateTime ate;
        switch (period) {
            case "day" -> {
                de = now.minusHours(24);
                ate = now;
            }
            case "week" -> {
                de = now.minusDays(7);
                ate = now;
            }
            case "month" -> {
                de = now.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
                ate = de.plusMonths(1).minusSeconds(1);
            }
            default -> {
                return null;
            }
        }
        return new Janela(iso(de), iso(ate), de, ate);
    }

    /** datetime.isoformat() de um instante UTC. */
    static String iso(OffsetDateTime t) {
        OffsetDateTime u = t.withOffsetSameInstant(ZoneOffset.UTC);
        int micro = u.getNano() / 1000;
        return u.format(SEGUNDOS) + (micro == 0 ? "" : String.format(java.util.Locale.ROOT, ".%06d", micro))
                + "+00:00";
    }

    /** round(count * 100 / total, 1) do Python: half-even sobre o valor binario exato. */
    static double pct(long count, long total) {
        return new BigDecimal(count * 100.0 / total).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }

    private Janela periodo(String period) {
        Janela j = janela(period == null ? "month" : period, relogio.instant());
        if (j == null) {
            throw new PastoralException(400, "invalid_period", "Período inválido",
                    "Período não reconhecido. Use: day, week ou month.");
        }
        return j;
    }

    /** cache_page(60): Cache-Control: max-age=60 e Expires daqui a 60 s. */
    private <T> ResponseEntity<T> comCache(T corpo) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60)))
                .header("Expires", DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        relogio.instant().plusSeconds(60).atOffset(ZoneOffset.UTC)).replace("+0000", "GMT"))
                .body(corpo);
    }

    @GetMapping("/api/admin/metrics/overview/")
    ResponseEntity<Map<String, Object>> overview(@RequestParam(required = false) String period) {
        Janela j = periodo(period);
        String ag = StatusPedido.AGUARDANDO_PAGAMENTO.valor();
        Map<String, Object> linha = jdbc.queryForMap("""
                select count(*) as total,
                  count(*) filter (where status <> ?) as pagos,
                  count(*) filter (where status = 'enviada') as enviadas,
                  count(*) filter (where status in ('erro', 'aguardando_reenvio')) as erro,
                  coalesce(sum(valor_centavos) filter (where status <> ?), 0) as faturamento
                from orders_pedido where created_at between ? and ?""", ag, ag, j.de(), j.ate());
        long pagos = ((Number) linha.get("pagos")).longValue();
        long faturamento = ((Number) linha.get("faturamento")).longValue();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("period", period == null ? "month" : period);
        r.put("range", new Intervalo(j.start(), j.end()));
        r.put("pedidos_total", linha.get("total"));
        r.put("pedidos_pagos", pagos);
        r.put("pedidos_enviadas", linha.get("enviadas"));
        r.put("pedidos_erro", linha.get("erro"));
        r.put("faturamento_centavos", faturamento);
        r.put("faturamento_reais_str", Dinheiro.centavosParaReaisStr(faturamento));
        r.put("ticket_medio_centavos", pagos > 0 ? faturamento / pagos : 0);
        return comCache(r);
    }

    record Intervalo(String start, String end) {}

    record PorPlano(String plano_nome, int valor_centavos, long count, double pct) {}

    record PorCanal(String canal, long count) {}

    record Alerta(String id, String created_at, String nome, String plano, String status, String last_error) {}

    record Distribuicao(List<PorPlano> distribuicao_por_plano, List<PorCanal> distribuicao_por_canal,
            List<Alerta> alertas_erro) {}

    @GetMapping("/api/admin/metrics/distribution/")
    ResponseEntity<Distribuicao> distribution(@RequestParam(required = false) String period) {
        Janela j = periodo(period);
        String base = " from orders_pedido p join plans_plan pl on pl.id = p.plano_id where p.created_at between ? and ?"
                + " and p.status <> 'aguardando_pagamento'";
        List<PorPlano> porPlano = new java.util.ArrayList<>();
        jdbc.query("select pl.nome, pl.valor_centavos, count(p.id) as n" + base
                + " group by pl.nome, pl.valor_centavos order by n desc, pl.nome", rs -> {
                    porPlano.add(new PorPlano(rs.getString(1), rs.getInt(2), rs.getLong(3), 0));
                }, j.de(), j.ate());
        long total = porPlano.stream().mapToLong(PorPlano::count).sum();
        List<PorPlano> comPct = porPlano.stream()
                .map(x -> new PorPlano(x.plano_nome(), x.valor_centavos(), x.count(), pct(x.count(), total))).toList();
        // a ordem do Django e indefinida; aqui, por canal
        List<PorCanal> porCanal = jdbc.query("select upper(p.canal_entrega) as c, count(p.id)" + base
                + " group by p.canal_entrega order by c", (rs, i) -> new PorCanal(rs.getString(1), rs.getLong(2)),
                j.de(), j.ate());
        // fora do periodo, como no Django
        List<Alerta> alertas = pedidos.alertas(ALERTAS, PageRequest.of(0, 20)).stream().map(MetricasAdminController::alerta)
                .toList();
        return comCache(new Distribuicao(comPct, porCanal, alertas));
    }

    private static Alerta alerta(Pedido p) {
        // ORM do Django devolve UTC; nome[:20] e last_error[:100] contam code points
        return new Alerta(p.getId().toString(), iso(p.getCreatedAt()), cortar(p.getNome(), 20, true),
                p.getPlano().getNome(), p.getStatus().valor().toUpperCase(java.util.Locale.ROOT),
                p.getLastError() == null ? "" : cortar(p.getLastError(), 100, false));
    }

    private static String cortar(String s, int max, boolean reticencias) {
        if (s.codePointCount(0, s.length()) <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max)) + (reticencias ? "..." : "");
    }
}
