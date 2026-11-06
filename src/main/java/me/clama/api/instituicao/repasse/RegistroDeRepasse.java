package me.clama.api.instituicao.repasse;

import java.util.UUID;
import me.clama.api.pedido.Pedido;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * registrar_repasse (instituicoes/services/repasse.py): 20% do valor do pedido, com piso, para a instituicao escolhida.
 * So o webhook aprovado chama, na transacao dele. Idempotente por pedido (UNIQUE pedido_id, ON CONFLICT): o get_or_create
 * do Django. Nunca revertido (estorno e marcar-gratuito nao mexem), como no Django.
 */
@Component
public class RegistroDeRepasse {
    /** REPASSE_PERCENTUAL_PADRAO (instituicoes/constants.py). */
    static final int PERCENTUAL = 20;

    private final JdbcTemplate jdbc;

    RegistroDeRepasse(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** true se gravou agora; false sem instituicao, valor 0 ou repasse ja existente. */
    public boolean registrar(Pedido p) {
        if (p.getInstituicao() == null) {
            return false;
        }
        long valor = Math.floorDiv((long) p.getValorCentavos() * PERCENTUAL, 100);
        if (valor <= 0) {
            return false;
        }
        return jdbc.update("""
                INSERT INTO instituicoes_repasse (created_at, updated_at, id, pedido_id, instituicao_id,
                    valor_base_centavos, percentual, valor_centavos, status, transferido_em, comprovante)
                VALUES (now(), now(), ?, ?, ?, ?, ?, ?, 'registrado', NULL, '')
                ON CONFLICT (pedido_id) DO NOTHING""", UUID.randomUUID(), p.getId(), p.getInstituicao().getId(),
                p.getValorCentavos(), PERCENTUAL, (int) valor) == 1;
    }
}
