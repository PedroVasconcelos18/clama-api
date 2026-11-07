package me.clama.api.prompt;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import me.clama.api.comum.api.DatasDrf;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AdminPromptTemplateSerializer. */
public final class PromptDtos {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PromptDtos() {}

    public record Admin(UUID id, String nome, int versao, String system_prompt, JsonNode instrucoes_por_complexidade,
            boolean ativo, String created_at, String updated_at) {
        static Admin de(PromptTemplate p) {
            return new Admin(p.getId(), p.getNome(), p.getVersao(), p.getSystemPrompt(),
                    JSON.readTree(p.getInstrucoesPorComplexidade()), p.isAtivo(),
                    DatasDrf.dataHora(p.getCreatedAt()), DatasDrf.dataHora(p.getUpdatedAt()));
        }
    }

    public record Ativado(String status, String message) {}

    public record Previa(String oracao_preview, TemplateDaPrevia template) {}

    public record TemplateDaPrevia(UUID id, String nome, int versao) {}

    /** {"error": {"code"?, "message"}}: o code so existe nos creditos esgotados. */
    public record Erro(Detalhe error) {
        public record Detalhe(@JsonInclude(JsonInclude.Include.NON_NULL) String code, String message) {}
    }
}
