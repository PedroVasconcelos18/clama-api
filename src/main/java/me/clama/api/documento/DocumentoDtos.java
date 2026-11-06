package me.clama.api.documento;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import me.clama.api.comum.api.DatasDrf;

/** AdminDocumentoContextoSerializer (detalhe, com arquivo) e AdminDocumentoContextoListSerializer (lista). */
public final class DocumentoDtos {
    private DocumentoDtos() {}

    public record Detalhe(UUID id, String nome, String descricao, String arquivo, String tipo_mime, int tamanho_bytes,
            String tamanho_formatado, String anthropic_file_id, String data_sincronizacao, boolean ativo,
            boolean esta_sincronizado, String created_at, String updated_at) {
        /** base: esquema://host da requisicao (request.build_absolute_uri). */
        static Detalhe de(DocumentoContexto d, String base) {
            return new Detalhe(d.getId(), d.getNome(), d.getDescricao(), url(base, d.getArquivo()), d.getTipoMime(),
                    d.getTamanhoBytes(), tamanhoFormatado(d.getTamanhoBytes()), d.getAnthropicFileId(),
                    DatasDrf.dataHora(d.getDataSincronizacao()), d.isAtivo(), d.sincronizado(),
                    DatasDrf.dataHora(d.getCreatedAt()), DatasDrf.dataHora(d.getUpdatedAt()));
        }
    }

    public record Lista(UUID id, String nome, String descricao, String tipo_mime, int tamanho_bytes,
            String tamanho_formatado, String anthropic_file_id, String data_sincronizacao, boolean ativo,
            boolean esta_sincronizado, String created_at, String updated_at) {
        static Lista de(DocumentoContexto d) {
            return new Lista(d.getId(), d.getNome(), d.getDescricao(), d.getTipoMime(), d.getTamanhoBytes(),
                    tamanhoFormatado(d.getTamanhoBytes()), d.getAnthropicFileId(),
                    DatasDrf.dataHora(d.getDataSincronizacao()), d.isAtivo(), d.sincronizado(),
                    DatasDrf.dataHora(d.getCreatedAt()), DatasDrf.dataHora(d.getUpdatedAt()));
        }
    }

    public record Toggle(String status, String message, boolean ativo, String file_id) {}

    public record Erro(String status, String message) {}

    /** tamanho_formatado do modelo. */
    static String tamanhoFormatado(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return casas(bytes / 1024.0, 1) + " KB";
        }
        return casas(bytes / (1024.0 * 1024), 1) + " MB";
    }

    /** f"{x:.Nf}" do Python: valor binario exato, meio arredonda para o par (1.25 -> "1.2"). */
    static String casas(double x, int n) {
        return new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** FileField.to_representation: build_absolute_uri(MEDIA_URL + filepath_to_uri(nome)); sem arquivo, null. */
    static String url(String base, String nome) {
        if (nome == null || nome.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder(base).append("/media/");
        for (byte c : nome.replace('\\', '/').getBytes(StandardCharsets.UTF_8)) {
            // quote(safe="/~!*()'") do Python: letras, digitos e _.-~ nunca escapam
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "_.-~/!*()'".indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(HexFormat.of().withUpperCase().toHexDigits(c));
            }
        }
        return b.toString();
    }
}
