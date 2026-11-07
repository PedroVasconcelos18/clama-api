package me.clama.api.pedido.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import me.clama.api.comum.api.DatasDrf;
import me.clama.api.comum.api.ParametrosDeQuery;
import me.clama.api.identidade.Usuario;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CustomerPedidosListView: so os pedidos do usuario logado (sem exigir senha em dia), sem paginacao. from/to sao dias
 * em America/Sao_Paulo (00:00:00 e 23:59:59.999999), inclusivos; data invalida e ignorada em silencio.
 */
@RestController
class PedidosDoClienteController {
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo"); // TIME_ZONE do Django
    // ponytail: sentinelas no lugar de "sem filtro"; dentro do intervalo do timestamptz
    private static final OffsetDateTime SEM_INICIO = OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime SEM_FIM = OffsetDateTime.of(9999, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);

    private final PedidoRepository pedidos;
    private final TransactionTemplate tx;

    PedidosDoClienteController(PedidoRepository pedidos, TransactionTemplate tx) {
        this.pedidos = pedidos;
        this.tx = tx;
    }

    /** CustomerPedidoListSerializer; oracao_gerada so quando enviada. */
    record Item(java.util.UUID id, String status, String plano, String valor_reais_str, int valor_centavos,
            boolean eh_gratuito, String canal_entrega, String created_at, String oracao_gerada) {
        static Item de(Pedido p) {
            String oracao = p.getStatus() == StatusPedido.ENVIADA && !p.getOracaoGerada().isEmpty()
                    ? p.getOracaoGerada() : null;
            return new Item(p.getId(), p.getStatus().valor(), p.getPlano().getNome(), p.valorReaisStr(),
                    p.getValorCentavos(), p.isEhGratuito(), p.getCanalEntrega().valor(),
                    DatasDrf.dataHora(p.getCreatedAt()), oracao);
        }
    }

    @GetMapping("/api/customer/pedidos/")
    List<Item> listar(@AuthenticationPrincipal Usuario eu, HttpServletRequest req) {
        LocalDate dia = dia(ParametrosDeQuery.ultimo(req, "from"));
        OffsetDateTime inicio = dia == null ? SEM_INICIO : dia.atStartOfDay(FUSO).toOffsetDateTime();
        dia = dia(ParametrosDeQuery.ultimo(req, "to"));
        OffsetDateTime fim = dia == null ? SEM_FIM
                : dia.atTime(LocalTime.of(23, 59, 59, 999_999_000)).atZone(FUSO).toOffsetDateTime();
        return tx.execute(st -> pedidos.doUsuario(eu.getId(), inicio, fim).stream().map(Item::de).toList());
    }

    /** date.fromisoformat do Python 3.11+: AAAA-MM-DD ou AAAAMMDD; o resto e ignorado (null). */
    static LocalDate dia(String v) {
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(v, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
