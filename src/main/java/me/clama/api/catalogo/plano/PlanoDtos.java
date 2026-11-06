package me.clama.api.catalogo.plano;

import java.util.UUID;
import me.clama.api.comum.Dinheiro;
import me.clama.api.identidade.api.UsuarioDtos;

/** PlanSerializer (publico) e AdminPlanSerializer. */
public final class PlanoDtos {
    private PlanoDtos() {}

    public record Publico(UUID id, String nome, int valor_centavos, String valor_reais_str, String descricao,
            String complexidade, int ordem) {
        static Publico de(Plano p) {
            return new Publico(p.getId(), p.getNome(), p.getValorCentavos(),
                    Dinheiro.centavosParaReaisStr(p.getValorCentavos()), p.getDescricao(), p.getComplexidade(),
                    p.getOrdem());
        }
    }

    public record Admin(UUID id, String nome, int valor_centavos, String valor_reais_str, String descricao,
            String complexidade, int ordem, boolean ativo, String created_at, String updated_at) {
        static Admin de(Plano p) {
            return new Admin(p.getId(), p.getNome(), p.getValorCentavos(),
                    Dinheiro.centavosParaReaisStr(p.getValorCentavos()), p.getDescricao(), p.getComplexidade(),
                    p.getOrdem(), p.isAtivo(), UsuarioDtos.dataHora(p.getCreatedAt()),
                    UsuarioDtos.dataHora(p.getUpdatedAt()));
        }
    }

    public record Desativado(String status, String message) {}
}
