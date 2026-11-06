package me.clama.api.catalogo.plano;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.erro.NaoEncontrado;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** AdminPlanViewSet: sem DELETE (o 405 vem do Spring). Acesso admin: SegurancaConfig (/api/admin/**). */
@RestController
class PlanosAdminController {
    private static final String ID = "{id}/";
    private static final String MSG_NOME_DUPLICADO = "Já existe um plano ativo com este nome.";

    private final PlanoRepository planos;
    private final TransactionTemplate tx;

    PlanosAdminController(PlanoRepository planos, TransactionTemplate tx) {
        this.planos = planos;
        this.tx = tx;
    }

    /** Campos validados; null = ausente (so POST/PUT exigem nome, valor_centavos e descricao). */
    private record Entrada(String nome, Integer valor, String descricao, String complexidade, Integer ordem,
            Boolean ativo) {}

    /** Ordem dos campos = ordem do serializer (a dos erros). */
    private static Entrada ler(JsonNode corpo, boolean parcial) {
        CamposDrf c = new CamposDrf(corpo);
        boolean obrigatorio = !parcial;
        Entrada e = new Entrada(
                c.texto("nome", obrigatorio, CamposDrf.maxLength(80)),
                c.inteiro("valor_centavos", obrigatorio, 1, Integer.MAX_VALUE),
                c.texto("descricao", obrigatorio, x -> List.of()),
                c.escolha("complexidade", false, Plano.COMPLEXIDADES),
                c.inteiro("ordem", false, 0, Short.MAX_VALUE),
                c.booleano("ativo", false));
        c.validar();
        return e;
    }

    @GetMapping("/api/admin/planos/")
    List<PlanoDtos.Admin> listar() {
        return planos.findAllByOrderByOrdem().stream().map(PlanoDtos.Admin::de).toList();
    }

    @GetMapping("/api/admin/planos/" + ID)
    PlanoDtos.Admin detalhe(@PathVariable("id") String rota) {
        return PlanoDtos.Admin.de(planos.findById(NaoEncontrado.uuidDaRota(rota)).orElseThrow(() -> new NaoEncontrado("Plan")));
    }

    @PostMapping("/api/admin/planos/")
    @ResponseStatus(HttpStatus.CREATED)
    PlanoDtos.Admin criar(@RequestBody(required = false) JsonNode corpo) {
        Entrada e = ler(corpo, false);
        return tx.execute(st -> {
            validarNome(e, null);
            Plano p = new Plano();
            aplicar(p, e);
            return PlanoDtos.Admin.de(planos.saveAndFlush(p));
        });
    }

    @PutMapping("/api/admin/planos/" + ID)
    PlanoDtos.Admin atualizar(@PathVariable("id") String rota, @RequestBody(required = false) JsonNode corpo) {
        return salvar(NaoEncontrado.uuidDaRota(rota), corpo, false);
    }

    @PatchMapping("/api/admin/planos/" + ID)
    PlanoDtos.Admin atualizarParcial(@PathVariable("id") String rota, @RequestBody(required = false) JsonNode corpo) {
        return salvar(NaoEncontrado.uuidDaRota(rota), corpo, true);
    }

    @PostMapping("/api/admin/planos/" + ID + "desativar/")
    PlanoDtos.Desativado desativar(@PathVariable("id") String rota) {
        UUID id = NaoEncontrado.uuidDaRota(rota);
        tx.executeWithoutResult(st -> {
            Plano p = planos.findById(id).orElseThrow(() -> new NaoEncontrado("Plan"));
            p.setAtivo(false);
            p.tocar();
        });
        return new PlanoDtos.Desativado("ok", "Plano desativado");
    }


    /** get_object() antes do serializer: 404 vence o 400. */
    private PlanoDtos.Admin salvar(UUID id, JsonNode corpo, boolean parcial) {
        if (!planos.existsById(id)) {
            throw new NaoEncontrado("Plan");
        }
        Entrada e = ler(corpo, parcial);
        return tx.execute(st -> {
            Plano p = planos.findById(id).orElseThrow(() -> new NaoEncontrado("Plan"));
            validarNome(e, id);
            aplicar(p, e);
            p.tocar();
            return PlanoDtos.Admin.de(planos.saveAndFlush(p));
        });
    }

    /** validate(): ativo ausente vale True (mesmo no PATCH de plano inativo), como no serializer. */
    private void validarNome(Entrada e, UUID id) {
        boolean ativo = e.ativo() == null || e.ativo();
        if (e.nome() == null || !ativo) {
            return;
        }
        boolean existe = id == null ? planos.existsByNomeAndAtivoTrue(e.nome())
                : planos.existsByNomeAndAtivoTrueAndIdNot(e.nome(), id);
        if (existe) {
            throw new CamposDrf.Invalidos(Map.of("nome", List.of(MSG_NOME_DUPLICADO)));
        }
    }

    private static void aplicar(Plano p, Entrada e) {
        if (e.nome() != null) {
            p.setNome(e.nome());
        }
        if (e.valor() != null) {
            p.setValorCentavos(e.valor());
        }
        if (e.descricao() != null) {
            p.setDescricao(e.descricao());
        }
        if (e.complexidade() != null) {
            p.setComplexidade(e.complexidade());
        }
        if (e.ordem() != null) {
            p.setOrdem(e.ordem());
        }
        if (e.ativo() != null) {
            p.setAtivo(e.ativo());
        }
    }
}
