package me.clama.api.prompt;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.erro.NaoEncontrado;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * AdminPromptTemplateViewSet: get e post apenas (o 405 vem do Spring). O preview fica para a Etapa 4 (404 ate la).
 * Acesso admin: SegurancaConfig (/api/admin/**).
 */
@RestController
class PromptsAdminController {
    private static final String ID = "{id}/";
    private static final Pattern UUID_DJANGO =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String MSG_NOME_DUPLICADO = "Template de Prompt com este Nome já existe.";

    private final PromptTemplateRepository prompts;
    private final TransactionTemplate tx;

    PromptsAdminController(PromptTemplateRepository prompts, TransactionTemplate tx) {
        this.prompts = prompts;
        this.tx = tx;
    }

    @GetMapping("/api/admin/prompts/")
    List<PromptDtos.Admin> listar() {
        return prompts.findAllByOrderByVersaoDescCreatedAtAsc().stream().map(PromptDtos.Admin::de).toList();
    }

    @GetMapping("/api/admin/prompts/" + ID)
    PromptDtos.Admin detalhe(@PathVariable("id") String rota) {
        return PromptDtos.Admin.de(prompts.findById(uuid(rota)).orElseThrow(() -> new NaoEncontrado("PromptTemplate")));
    }

    /** versao, ativo e ids vindos do cliente sao ignorados (read_only); nasce inativo. */
    @PostMapping("/api/admin/prompts/")
    @ResponseStatus(HttpStatus.CREATED)
    PromptDtos.Admin criar(@RequestBody(required = false) JsonNode corpo) {
        CamposDrf c = new CamposDrf(corpo);
        // UniqueValidator vem antes do MaxLengthValidator no DRF: os dois erros saem juntos
        String nome = c.texto("nome", true, s -> {
            List<String> msgs = new java.util.ArrayList<>();
            if (prompts.existsByNome(s)) {
                msgs.add(MSG_NOME_DUPLICADO);
            }
            msgs.addAll(CamposDrf.maxLength(80).apply(s));
            return msgs;
        });
        String sistema = c.texto("system_prompt");
        JsonNode instrucoes = c.get("instrucoes_por_complexidade");
        if (instrucoes != null && instrucoes.isNull()) {
            c.erro("instrucoes_por_complexidade", CamposDrf.NULO);
        }
        c.validar();
        try {
            return tx.execute(st -> {
                PromptTemplate p = new PromptTemplate();
                p.setNome(nome);
                p.setVersao(prompts.maxVersaoPorNome(nome).orElse(0) + 1);
                p.setSystemPrompt(sistema);
                if (instrucoes != null) {
                    p.setInstrucoesPorComplexidade(instrucoes.toString());
                }
                return PromptDtos.Admin.de(prompts.saveAndFlush(p));
            });
        } catch (DataIntegrityViolationException e) { // criacao concorrente do mesmo nome: perdeu a corrida
            throw new CamposDrf.Invalidos(Map.of("nome", List.of(MSG_NOME_DUPLICADO)));
        }
    }

    /** save() do modelo: desativa os outros e ativa este, serializado. */
    @PostMapping("/api/admin/prompts/" + ID + "ativar/")
    PromptDtos.Ativado ativar(@PathVariable("id") String rota) {
        UUID id = uuid(rota);
        return tx.execute(st -> {
            prompts.travarAtivacao();
            PromptTemplate p = prompts.findById(id).orElseThrow(() -> new NaoEncontrado("PromptTemplate"));
            prompts.desativarOutros(id);
            p.setAtivo(true);
            p.tocar();
            prompts.saveAndFlush(p);
            return new PromptDtos.Ativado("ok", "Prompt " + p.getNome() + " v" + p.getVersao() + " ativado");
        });
    }

    private static UUID uuid(String rota) {
        if (!UUID_DJANGO.matcher(rota).matches()) {
            throw new NaoEncontrado();
        }
        return UUID.fromString(rota);
    }
}
