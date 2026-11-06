package me.clama.api.prompt;

import java.util.List;
import org.springframework.stereotype.Service;

/** get_active() do Django. A busca fica aqui: no repositorio o proxy do Spring Data embrulharia a excecao. */
@Service
public class PromptAtivo {
    /** Equivale ao no_active_prompt do Django. */
    public static class SemPromptAtivo extends RuntimeException {
        public SemPromptAtivo() {
            super("Nenhum template de prompt ativo encontrado");
        }
    }

    /** MultipleObjectsReturned do Django. */
    public static class MaisDeUmPromptAtivo extends RuntimeException {
        public MaisDeUmPromptAtivo(int quantos) {
            super("mais de um PromptTemplate ativo: " + quantos);
        }
    }

    private final PromptTemplateRepository prompts;

    PromptAtivo(PromptTemplateRepository prompts) {
        this.prompts = prompts;
    }

    public PromptTemplate obter() {
        List<PromptTemplate> ativos = prompts.findByAtivoTrue();
        if (ativos.isEmpty()) throw new SemPromptAtivo();
        if (ativos.size() > 1) throw new MaisDeUmPromptAtivo(ativos.size());
        return ativos.get(0);
    }
}
