package me.clama.api.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.erro.NaoEncontrado;
import me.clama.api.oracao.ClienteClaude;
import me.clama.api.oracao.CreditosEsgotados;
import me.clama.api.oracao.MontagemDoPrompt;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * AdminPromptTemplateViewSet: get e post apenas (o 405 vem do Spring). O preview chama a Claude (ClienteClaude.gerarPrevia).
 * Acesso admin: SegurancaConfig (/api/admin/**).
 */
@RestController
class PromptsAdminController {
    private static final String ID = "{id}/";
    private static final String MSG_NOME_DUPLICADO = "Template de Prompt com este Nome já existe.";

    private final PromptTemplateRepository prompts;
    private final TransactionTemplate tx;

    private final ClienteClaude claude;

    PromptsAdminController(PromptTemplateRepository prompts, TransactionTemplate tx, ClienteClaude claude) {
        this.prompts = prompts;
        this.tx = tx;
        this.claude = claude;
    }

    @GetMapping("/api/admin/prompts/")
    List<PromptDtos.Admin> listar() {
        return prompts.findAllByOrderByVersaoDescCreatedAtAsc().stream().map(PromptDtos.Admin::de).toList();
    }

    @GetMapping("/api/admin/prompts/" + ID)
    PromptDtos.Admin detalhe(@PathVariable("id") String rota) {
        return PromptDtos.Admin.de(prompts.findById(NaoEncontrado.uuidDaRota(rota)).orElseThrow(() -> new NaoEncontrado("PromptTemplate")));
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
        UUID id = NaoEncontrado.uuidDaRota(rota);
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

    /**
     * Previa sem persistir, sem documentos e sem throttle (o de 5/min do Django nunca valeu). O 404 vem antes da
     * validacao do corpo; pedido_exemplo e DictField(child=CharField), com o erro aninhado por chave.
     */
    @PostMapping("/api/admin/prompts/" + ID + "preview/")
    ResponseEntity<Object> previa(@PathVariable("id") String rota, @RequestBody(required = false) JsonNode corpo) {
        PromptTemplate t = prompts.findById(NaoEncontrado.uuidDaRota(rota))
                .orElseThrow(() -> new NaoEncontrado("PromptTemplate"));
        JsonNode ex = new CamposDrf(corpo).get("pedido_exemplo");
        if (ex == null || !ex.isObject()) {
            throw new CamposDrf.Invalidos(Map.of("pedido_exemplo", List.of(ex == null ? CamposDrf.OBRIGATORIO
                    : ex.isNull() ? CamposDrf.NULO : "Esperava um dicionário de itens, mas recebeu tipo \""
                            + CamposDrf.tipoPython(ex) + "\".")));
        }
        CamposDrf itens = new CamposDrf(ex);
        Map<String, String> exemplo = new LinkedHashMap<>();
        ex.propertyNames().forEach(k -> exemplo.put(k, itens.texto(k)));
        try {
            itens.validar();
        } catch (CamposDrf.Invalidos e) { // erro aninhado por chave, como o DictField
            return ResponseEntity.badRequest().body(Map.of("pedido_exemplo", e.erros()));
        }
        try {
            return ResponseEntity.ok(new PromptDtos.Previa(claude.gerarPrevia(t.getSystemPrompt(),
                    MontagemDoPrompt.mensagemDePrevia(exemplo, t), exemplo.getOrDefault("nome", "Maria")),
                    new PromptDtos.TemplateDaPrevia(t.getId(), t.getNome(), t.getVersao())));
        } catch (CreditosEsgotados e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new PromptDtos.Erro(
                    new PromptDtos.Erro.Detalhe("anthropic_no_credits", "A API da Anthropic está sem créditos. "
                            + "Recarregue os créditos para gerar previews.")));
        } catch (RuntimeException e) { // como o except Exception do Django: vaza a mensagem
            return ResponseEntity.internalServerError().body(new PromptDtos.Erro(
                    new PromptDtos.Erro.Detalhe(null, String.valueOf(e.getMessage()))));
        }
    }
}
