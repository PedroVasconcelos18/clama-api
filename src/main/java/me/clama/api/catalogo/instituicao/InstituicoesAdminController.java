package me.clama.api.catalogo.instituicao;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.erro.NaoEncontrado;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * AdminInstituicaoViewSet (JSON, multipart e form): sem DELETE (o 405 vem do Spring). Acesso admin: SegurancaConfig.
 * A logo chega em logo_file e vira data URI em logo.
 */
@RestController
class InstituicoesAdminController {
    private static final String ID = "{id}/";
    private static final String BASE = "/api/admin/instituicoes/";
    /** converter uuid do Django: so minusculas. Outra coisa e URL desconhecida (404 "Não encontrado."). */
    private static final Pattern UUID_DJANGO =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final List<String> LOGO_MIMES = List.of("image/png", "image/jpeg", "image/svg+xml", "image/webp",
            "image/gif");
    private static final int LOGO_MAX_BYTES = 250 * 1024;
    private static final String NAO_ARQUIVO = "O dado submetido não era um arquivo. Cheque o tipo de codificação no formulário.";

    private final InstituicaoRepository instituicoes;
    private final TransactionTemplate tx;

    InstituicoesAdminController(InstituicaoRepository instituicoes, TransactionTemplate tx) {
        this.instituicoes = instituicoes;
        this.tx = tx;
    }

    /** Campos validados; null = ausente. logo ja e o data URI. */
    private record Entrada(String nome, String logo, Boolean ativo, Integer ordem) {}

    /** Ordem dos campos = ordem do serializer (a dos erros): nome, logo_file, ativo, ordem. */
    private static Entrada ler(JsonNode corpo, MultipartFile arquivo, boolean parcial) {
        CamposDrf c = new CamposDrf(corpo);
        String nome = c.texto("nome", !parcial, CamposDrf.maxLength(120));
        String logo = logo(c, arquivo);
        Boolean ativo = c.booleano("ativo", false);
        Integer ordem = c.inteiro("ordem", false, 0, Integer.MAX_VALUE);
        c.validar();
        return new Entrada(nome, logo, ativo, ordem);
    }

    /** validate_logo_file + _arquivo_para_data_uri. logo_file nulo ou ausente nao mexe na logo. */
    private static String logo(CamposDrf c, MultipartFile arquivo) {
        if (arquivo == null) {
            JsonNode v = c.get("logo_file");
            return v != null && !v.isNull() ? c.erro("logo_file", NAO_ARQUIVO) : null;
        }
        if (arquivo.isEmpty()) {
            return c.erro("logo_file", "O arquivo submetido está vázio.");
        }
        String mime = arquivo.getContentType() == null ? "" : arquivo.getContentType();
        if (!LOGO_MIMES.contains(mime)) {
            return c.erro("logo_file", "Formato de imagem não suportado. Use PNG, JPG, SVG, WEBP ou GIF.");
        }
        if (arquivo.getSize() > LOGO_MAX_BYTES) {
            return c.erro("logo_file",
                    "A imagem excede o limite de " + LOGO_MAX_BYTES / 1024 + " KB. Envie uma versão menor.");
        }
        try {
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(arquivo.getBytes());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * QueryDict do DRF (request.data de formulario): BooleanField ausente vale False fora do PATCH, e inteiro vazio
     * conta como ausente. Campo logo_file em texto (nao arquivo) fica no objeto e vira erro em logo().
     */
    private static ObjectNode formulario(Map<String, String> params, boolean parcial) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        params.forEach((k, v) -> {
            if (!(k.equals("ordem") && v.isEmpty())) {
                o.put(k, v);
            }
        });
        if (!parcial && !o.has("ativo")) {
            o.put("ativo", false);
        }
        return o;
    }

    @GetMapping(BASE)
    List<InstituicaoDtos.Admin> listar() {
        return instituicoes.findAllByOrderByOrdemAscNomeAsc().stream().map(InstituicaoDtos.Admin::de).toList();
    }

    @GetMapping(BASE + ID)
    InstituicaoDtos.Admin detalhe(@PathVariable("id") String rota) {
        return InstituicaoDtos.Admin.de(instituicoes.findById(uuid(rota)).orElseThrow(this::nao));
    }

    @PostMapping(BASE)
    @ResponseStatus(HttpStatus.CREATED)
    InstituicaoDtos.Admin criar(@RequestBody(required = false) JsonNode corpo) {
        return criar(ler(corpo, null, false));
    }

    @PostMapping(value = BASE, consumes = {MediaType.MULTIPART_FORM_DATA_VALUE,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    @ResponseStatus(HttpStatus.CREATED)
    InstituicaoDtos.Admin criarFormulario(@RequestParam Map<String, String> params,
            @RequestPart(name = "logo_file", required = false) MultipartFile arquivo) {
        return criar(ler(formulario(params, false), arquivo, false));
    }

    @PutMapping(BASE + ID)
    InstituicaoDtos.Admin atualizar(@PathVariable("id") String rota, @RequestBody(required = false) JsonNode corpo) {
        UUID id = existente(rota);
        return salvar(id, ler(corpo, null, false));
    }

    @PutMapping(value = BASE + ID, consumes = {MediaType.MULTIPART_FORM_DATA_VALUE,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    InstituicaoDtos.Admin atualizarFormulario(@PathVariable("id") String rota,
            @RequestParam Map<String, String> params,
            @RequestPart(name = "logo_file", required = false) MultipartFile arquivo) {
        UUID id = existente(rota);
        return salvar(id, ler(formulario(params, false), arquivo, false));
    }

    @PatchMapping(BASE + ID)
    InstituicaoDtos.Admin atualizarParcial(@PathVariable("id") String rota,
            @RequestBody(required = false) JsonNode corpo) {
        UUID id = existente(rota);
        return salvar(id, ler(corpo, null, true));
    }

    @PatchMapping(value = BASE + ID, consumes = {MediaType.MULTIPART_FORM_DATA_VALUE,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    InstituicaoDtos.Admin atualizarParcialFormulario(@PathVariable("id") String rota,
            @RequestParam Map<String, String> params,
            @RequestPart(name = "logo_file", required = false) MultipartFile arquivo) {
        UUID id = existente(rota);
        return salvar(id, ler(formulario(params, true), arquivo, true));
    }

    @PostMapping(BASE + ID + "desativar/")
    InstituicaoDtos.Desativada desativar(@PathVariable("id") String rota) {
        UUID id = uuid(rota);
        tx.executeWithoutResult(st -> {
            Instituicao i = instituicoes.findById(id).orElseThrow(this::nao);
            i.setAtivo(false);
            i.tocar();
        });
        return new InstituicaoDtos.Desativada("ok", "Instituição desativada");
    }

    private InstituicaoDtos.Admin criar(Entrada e) {
        return tx.execute(st -> {
            Instituicao i = new Instituicao();
            aplicar(i, e);
            return InstituicaoDtos.Admin.de(instituicoes.saveAndFlush(i));
        });
    }

    private InstituicaoDtos.Admin salvar(UUID id, Entrada e) {
        return tx.execute(st -> {
            Instituicao i = instituicoes.findById(id).orElseThrow(this::nao);
            aplicar(i, e);
            i.tocar();
            return InstituicaoDtos.Admin.de(instituicoes.saveAndFlush(i));
        });
    }

    private NaoEncontrado nao() {
        return new NaoEncontrado("Instituicao");
    }

    private static UUID uuid(String rota) {
        if (!UUID_DJANGO.matcher(rota).matches()) {
            throw new NaoEncontrado();
        }
        return UUID.fromString(rota);
    }

    /** get_object() antes do serializer: 404 vence o 400. */
    private UUID existente(String rota) {
        UUID id = uuid(rota);
        if (!instituicoes.existsById(id)) {
            throw nao();
        }
        return id;
    }

    private static void aplicar(Instituicao i, Entrada e) {
        if (e.nome() != null) {
            i.setNome(e.nome());
        }
        if (e.logo() != null) {
            i.setLogo(e.logo());
        }
        if (e.ativo() != null) {
            i.setAtivo(e.ativo());
        }
        if (e.ordem() != null) {
            i.setOrdem(e.ordem());
        }
    }
}
