package me.clama.api.catalogo.instituicao;

import java.util.UUID;
import me.clama.api.comum.api.DatasDrf;

/** InstituicaoSerializer (publico) e AdminInstituicaoSerializer. */
public final class InstituicaoDtos {
    private InstituicaoDtos() {}

    public record Publico(UUID id, String nome, String logo) {
        static Publico de(Instituicao i) {
            return new Publico(i.getId(), i.getNome(), i.getLogo());
        }
    }

    public record Admin(UUID id, String nome, String logo, boolean ativo, int ordem, String created_at,
            String updated_at) {
        static Admin de(Instituicao i) {
            return new Admin(i.getId(), i.getNome(), i.getLogo(), i.isAtivo(), i.getOrdem(),
                    DatasDrf.dataHora(i.getCreatedAt()), DatasDrf.dataHora(i.getUpdatedAt()));
        }
    }

    public record Desativada(String status, String message) {}
}
