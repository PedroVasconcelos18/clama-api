package me.clama.api.documento;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.api.FormularioDjango;
import me.clama.api.comum.erro.NaoEncontrado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * AdminDocumentoContextoViewSet (ModelViewSet, so multipart/form). Cria sem sincronizar; o toggle_ativo sincroniza
 * com a Files API ao ativar. Acesso admin: SegurancaConfig (/api/admin/**).
 */
@RestController
class DocumentosAdminController {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentosAdminController.class);
    private static final String BASE = "/api/admin/documentos/";
    private static final String ID = "{id}/";
    /** FileExtensionValidator(["pdf","txt","docx"]) e o mimetypes.guess_type de cada uma. */
    private static final Map<String, String> MIME = Map.of("pdf", "application/pdf", "txt", "text/plain", "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final String NAO_ARQUIVO =
            "O dado submetido não era um arquivo. Cheque o tipo de codificação no formulário.";
    private static final double MB = 1024 * 1024;

    private final DocumentoRepository documentos;
    private final ArmazenamentoLocal armazenamento;
    private final ArquivosAnthropic anthropic;
    private final TransactionTemplate tx;
    private final long maximo;

    DocumentosAdminController(DocumentoRepository documentos, ArmazenamentoLocal armazenamento,
            ArquivosAnthropic anthropic, TransactionTemplate tx,
            @Value("${clama.documentos.tamanho-maximo:100MB}") DataSize maximo) {
        this.documentos = documentos;
        this.armazenamento = armazenamento;
        this.anthropic = anthropic;
        this.tx = tx;
        this.maximo = maximo.toBytes();
    }

    /** Campos validados; null = ausente. */
    private record Entrada(String nome, String descricao, FormularioDjango.Arquivo arquivo, Boolean ativo) {}

    @GetMapping(BASE)
    List<DocumentoDtos.Lista> listar() {
        return documentos.findAllByOrderByCreatedAtDesc().stream().map(DocumentoDtos.Lista::de).toList();
    }

    @GetMapping(BASE + ID)
    DocumentoDtos.Detalhe detalhe(@PathVariable("id") String rota, HttpServletRequest req) {
        return DocumentoDtos.Detalhe.de(buscar(rota), base(req));
    }

    @PostMapping(BASE)
    @ResponseStatus(HttpStatus.CREATED)
    DocumentoDtos.Detalhe criar(HttpServletRequest req) {
        Entrada e = ler(FormularioDjango.de(req), false);
        DocumentoContexto d = new DocumentoContexto(UUID.randomUUID());
        // serializer.create: tipo e tamanho so na criacao
        d.setTipoMime(MIME.get(extensao(e.arquivo().nome())));
        d.setTamanhoBytes((int) e.arquivo().tamanho());
        aplicar(d, e);
        return DocumentoDtos.Detalhe.de(tx.execute(st -> documentos.saveAndFlush(d)), base(req));
    }

    @PutMapping(BASE + ID)
    DocumentoDtos.Detalhe atualizar(@PathVariable("id") String rota, HttpServletRequest req) {
        return salvar(rota, req, false);
    }

    @PatchMapping(BASE + ID)
    DocumentoDtos.Detalhe atualizarParcial(@PathVariable("id") String rota, HttpServletRequest req) {
        return salvar(rota, req, true);
    }

    /** destroy: se sincronizado, apaga na Anthropic antes; falha la nao remove aqui. O arquivo local fica (Django). */
    @DeleteMapping(BASE + ID)
    ResponseEntity<?> apagar(@PathVariable("id") String rota) {
        DocumentoContexto d = buscar(rota);
        if (d.sincronizado()) {
            try {
                anthropic.apagar(d.getAnthropicFileId());
            } catch (ArquivosAnthropic.Falha f) {
                LOG.error("Erro ao deletar arquivo na Anthropic documento_id={} file_id={} error={}", d.getId(),
                        d.getAnthropicFileId(), f.getMessage());
                return ResponseEntity.status(500).body(new DocumentoDtos.Erro("error",
                        "Falha ao remover da Anthropic: " + f.getMessage() + ". Documento não foi removido."));
            }
        }
        tx.executeWithoutResult(st -> documentos.deleteById(d.getId()));
        return ResponseEntity.noContent().build();
    }

    /**
     * toggle_ativo: ao ativar sem file_id, sobe o arquivo local para a Anthropic primeiro (fora da transacao: a
     * chamada leva ate 60 s). Falha la, ou arquivo local sumido, e 500 e o documento continua inativo.
     */
    @PostMapping(BASE + ID + "toggle_ativo/")
    ResponseEntity<?> toggleAtivo(@PathVariable("id") String rota) {
        DocumentoContexto lido = buscar(rota);
        boolean novo = !lido.isAtivo();
        String fileId = null;
        if (novo && !lido.sincronizado()) {
            String caminho = lido.getArquivo();
            try {
                fileId = anthropic.enviar(lido.getId(), caminho.substring(caminho.lastIndexOf('/') + 1),
                        lido.getTipoMime(), armazenamento.caminho(caminho));
                LOG.info("Documento sincronizado ao ativar documento_id={} file_id={}", lido.getId(), fileId);
            } catch (ArquivosAnthropic.Falha f) {
                LOG.error("Erro ao sincronizar documento ao ativar documento_id={} error={}", lido.getId(),
                        f.getMessage());
                return ResponseEntity.status(500)
                        .body(new DocumentoDtos.Erro("error", "Falha ao sincronizar com Anthropic: " + f.getMessage()));
            }
        }
        String sincronizado = fileId;
        DocumentoContexto d = tx.execute(st -> {
            DocumentoContexto atual = documentos.findById(lido.getId()).orElseThrow(() -> {
                if (sincronizado != null) {
                    LOG.warn("Documento sumiu apos o upload na Anthropic; file_id orfao file_id={} documento_id={}",
                            sincronizado, lido.getId());
                }
                return nao();
            });
            if (sincronizado != null) {
                atual.sincronizar(sincronizado);
            }
            atual.setAtivo(novo);
            atual.tocar();
            return documentos.saveAndFlush(atual);
        });
        return ResponseEntity.ok(new DocumentoDtos.Toggle("ok",
                "Documento '" + d.getNome() + "' " + (d.isAtivo() ? "ativado" : "desativado") + ".", d.isAtivo(),
                d.getAnthropicFileId()));
    }

    /** Upload acima do limite do multipart: o validate_arquivo do Django (o tamanho vem do Content-Length). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, List<String>>> grandeDemais(HttpServletRequest req) {
        return ResponseEntity.badRequest().body(Map.of("arquivo", List.of(excede(req.getContentLengthLong()))));
    }

    /** get_object() antes do serializer: 404 vence o 400 e o 415. */
    private DocumentoDtos.Detalhe salvar(String rota, HttpServletRequest req, boolean parcial) {
        UUID id = buscar(rota).getId();
        Entrada e = ler(FormularioDjango.de(req), parcial);
        DocumentoContexto d = tx.execute(st -> {
            DocumentoContexto atual = documentos.findById(id).orElseThrow(DocumentosAdminController::nao);
            // troca o arquivo e mantem tipo_mime/tamanho_bytes antigos, como o Django (so o create preenche)
            aplicar(atual, e);
            atual.tocar();
            return documentos.saveAndFlush(atual);
        });
        return DocumentoDtos.Detalhe.de(d, base(req));
    }

    /** Ordem dos campos = ordem do serializer (a dos erros): nome, descricao, arquivo, ativo. */
    private Entrada ler(FormularioDjango f, boolean parcial) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        f.campos().forEach(o::put);
        if (!parcial && !o.has("ativo")) {
            o.put("ativo", false); // BooleanField ausente em formulario vale False fora do PATCH
        }
        CamposDrf c = new CamposDrf(o);
        String nome = c.texto("nome", !parcial, CamposDrf.maxLength(200));
        String descricao = c.textoEmBranco("descricao");
        FormularioDjango.Arquivo arquivo = arquivo(c, f, !parcial);
        Boolean ativo = c.booleano("ativo", false);
        c.validar();
        return new Entrada(nome, descricao, arquivo, ativo);
    }

    /** FileField do DRF (to_internal_value), FileExtensionValidator e validate_arquivo, nessa ordem. */
    private FormularioDjango.Arquivo arquivo(CamposDrf c, FormularioDjango f, boolean obrigatorio) {
        FormularioDjango.Arquivo a = f.arquivos().get("arquivo");
        if (a == null) {
            if (c.get("arquivo") != null) {
                c.erro("arquivo", NAO_ARQUIVO);
            } else if (obrigatorio) {
                c.erro("arquivo", "Nenhum arquivo foi submetido.");
            }
            return null;
        }
        if (a.tamanho() == 0) {
            c.erro("arquivo", "O arquivo submetido está vázio.");
            return null;
        }
        int tamanhoDoNome = a.nome().codePointCount(0, a.nome().length());
        if (tamanhoDoNome > ArmazenamentoLocal.MAX_NOME) {
            c.erro("arquivo", "Certifique-se de que o nome do arquivo tenho no máximo " + ArmazenamentoLocal.MAX_NOME
                    + " caracteres (tem " + tamanhoDoNome + ").");
            return null;
        }
        String ext = extensao(a.nome());
        if (!MIME.containsKey(ext)) {
            c.erro("arquivo", "A extensão de arquivo “" + ext
                    + "” não é permitida. As extensões válidas são: pdf, txt, docx .");
            return null;
        }
        // MIME pela extensao. Desvio (ensaio da Etapa 9, #15): o Python 3.12.3 de producao (Railpack, sem
        // /etc/mime.types) nao conhece .docx, e o Django grava o content_type que o cliente mandou; aqui sai sempre o
        // oficial, que e o que o navegador manda
        if (a.tamanho() > maximo) {
            c.erro("arquivo", excede(a.tamanho()));
            return null;
        }
        return a;
    }

    private String excede(long tamanho) {
        return "Arquivo excede limite de " + DocumentoDtos.casas(maximo / MB, 0) + "MB. Tamanho: "
                + DocumentoDtos.casas(tamanho / MB, 1) + "MB";
    }

    private void aplicar(DocumentoContexto d, Entrada e) {
        if (e.nome() != null) {
            d.setNome(e.nome());
        }
        if (e.descricao() != null) {
            d.setDescricao(e.descricao());
        }
        if (e.ativo() != null) {
            d.setAtivo(e.ativo());
        }
        if (e.arquivo() != null) {
            // FileField.pre_save: o arquivo vai para o disco antes do INSERT/UPDATE
            try (InputStream in = e.arquivo().parte().getInputStream()) {
                d.setArquivo(armazenamento.salvar(d.getId(), e.arquivo().nome(), in));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /** Path(nome).suffix[1:].lower() do Python 3.12 (producao): ponto nem no inicio nem no fim. */
    static String extensao(String nome) {
        int i = nome.lastIndexOf('.');
        return i > 0 && i < nome.length() - 1 ? nome.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * request.build_absolute_uri: esquema pelo X-Forwarded-Proto quando presente (SECURE_PROXY_SSL_HEADER de
     * producao) e host do header Host.
     */
    private static String base(HttpServletRequest req) {
        String proto = req.getHeader("X-Forwarded-Proto");
        String esquema = proto == null ? req.getScheme()
                : "https".equals(proto.split(",", 2)[0].strip()) ? "https" : "http";
        String host = req.getHeader("Host");
        return esquema + "://" + (host != null ? host : req.getServerName() + ":" + req.getServerPort());
    }

    private DocumentoContexto buscar(String rota) {
        return documentos.findById(NaoEncontrado.uuidDaRota(rota)).orElseThrow(DocumentosAdminController::nao);
    }

    private static NaoEncontrado nao() {
        return new NaoEncontrado("DocumentoContexto");
    }
}
