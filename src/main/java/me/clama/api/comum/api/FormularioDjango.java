package me.clama.api.comum.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import me.clama.api.comum.texto.TextoPython;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * request.data de uma view com parser_classes = [MultiPartParser, FormParser]: campos de texto (ultimo valor) e
 * arquivos com as regras do MultiPartParser do Django 4.2. Parte com filename="" e campo de texto (o input de arquivo
 * vazio do navegador chega como ""); filename que o sanitize_file_name descarta ("..", "/") some.
 */
public record FormularioDjango(Map<String, String> campos, Map<String, Arquivo> arquivos) {
    /** UploadedFile: nome ja saneado, content_type sem parametros e minusculo. */
    public record Arquivo(String nome, String tipo, MultipartFile parte) {
        public long tamanho() {
            return parte.getSize();
        }
    }

    /** UnsupportedMediaType do DRF: 415 {"detail": ...}. */
    public static class TipoNaoSuportado extends RuntimeException {
        public TipoNaoSuportado(String tipo) {
            super("Tipo de mídia \"" + tipo + "\" não é suportado.", null, false, false);
        }
    }

    /** DATA_UPLOAD_MAX_MEMORY_SIZE: campo de texto maior que isso o Django recusa (RequestDataTooBig, 400). */
    private static final long MAX_CAMPO = 2_621_440;
    private static final Pattern ENTIDADE = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos);");

    /** Corpo vazio vale {} (DRF); multipart ou urlencoded viram campos; outro tipo e 415. */
    public static FormularioDjango de(HttpServletRequest req) {
        Map<String, String> campos = new LinkedHashMap<>();
        Map<String, Arquivo> arquivos = new LinkedHashMap<>();
        String tipo = req.getContentType() == null ? "" : req.getContentType();
        String base = tipo.toLowerCase(Locale.ROOT);
        boolean formulario = base.startsWith("multipart/form-data") || base.startsWith("application/x-www-form-urlencoded");
        if (!formulario) {
            if (req.getContentLengthLong() > 0 || req.getHeader("Transfer-Encoding") != null) {
                throw new TipoNaoSuportado(tipo);
            }
            return new FormularioDjango(campos, arquivos);
        }
        req.getParameterMap().forEach((k, v) -> campos.put(k, v[v.length - 1]));
        if (req instanceof MultipartHttpServletRequest m) {
            m.getMultiFileMap().forEach((campo, partes) -> {
                MultipartFile f = partes.getLast();
                String original = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
                if (original.isEmpty()) {
                    campos.putIfAbsent(campo, texto(f));
                    return;
                }
                String nome = nomeSaneado(original);
                if (nome != null) {
                    arquivos.put(campo, new Arquivo(nome, tipo(f.getContentType()), f));
                }
            });
        }
        return new FormularioDjango(campos, arquivos);
    }

    private static String texto(MultipartFile f) {
        if (f.getSize() > MAX_CAMPO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            return new String(f.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * MultiPartParser.sanitize_file_name: html.unescape, so o ultimo segmento de / e \, sem nao imprimiveis; "", "." e
     * ".." viram null (parte descartada). ponytail: so entidades numericas e as 5 do XML; o html.unescape conhece
     * as ~2200 do HTML5 (so importa para navegador que manda nome fora do charset do form, o admin manda UTF-8).
     */
    public static String nomeSaneado(String nome) {
        String s = ENTIDADE.matcher(nome).replaceAll(m -> {
            String e = m.group(1);
            int cp = switch (e) {
                case "amp" -> '&';
                case "lt" -> '<';
                case "gt" -> '>';
                case "quot" -> '"';
                case "apos" -> '\'';
                default -> {
                    try {
                        yield e.charAt(1) == 'x' || e.charAt(1) == 'X' ? Integer.parseInt(e.substring(2), 16)
                                : Integer.parseInt(e.substring(1));
                    } catch (NumberFormatException grande) {
                        yield 0xFFFD;
                    }
                }
            };
            return java.util.regex.Matcher.quoteReplacement(Character.isValidCodePoint(cp) && cp != 0
                    ? Character.toString(cp) : "�");
        });
        s = s.substring(s.lastIndexOf('/') + 1);
        s = s.substring(s.lastIndexOf('\\') + 1);
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(FormularioDjango::imprimivel).forEach(b::appendCodePoint);
        String r = b.toString();
        return List.of("", ".", "..").contains(r) ? null : r;
    }

    /** str.isprintable() do Python. */
    private static boolean imprimivel(int c) {
        if (c == ' ') {
            return true;
        }
        return switch (Character.getType(c)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /** content_type do UploadedFile: parse_header_parameters tira os parametros e poe em minusculas. */
    public static String tipo(String contentType) {
        if (contentType == null) {
            return "";
        }
        int i = contentType.indexOf(';');
        return TextoPython.strip(i < 0 ? contentType : contentType.substring(0, i)).toLowerCase(Locale.ROOT);
    }
}
