package me.clama.api.blog.wordpress;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** wordpress_webhook.py:traduzir_status e status_efetivo. Fail-closed: desconhecido vira rascunho, nunca publicado. */
public final class StatusWordPress {
    private static final Logger LOG = LoggerFactory.getLogger(StatusWordPress.class);
    private static final Map<String, String> MAPA = Map.of("publish", "publicado", "draft", "rascunho",
            "future", "agendado", "private", "privado", "pending", "pendente", "trash", "lixeira",
            "auto-draft", "rascunho", "inherit", "rascunho");

    private StatusWordPress() {}

    public static String traduzir(String statusWp) {
        String traduzido = MAPA.get(statusWp);
        if (traduzido != null) return traduzido;
        LOG.warn("wordpress_status_desconhecido (status_wp={}, fallback=rascunho)", statusWp);
        Sentry.captureMessage("Status desconhecido do WordPress: " + repr(statusWp), SentryLevel.WARNING);
        return "rascunho";
    }

    /** Protegido por senha e publicado vira privado: quem nao tem a senha nao le o post. */
    public static String efetivo(String statusWp, boolean protegidoPorSenha) {
        String traduzido = traduzir(statusWp);
        return protegidoPorSenha && "publicado".equals(traduzido) ? "privado" : traduzido;
    }

    /** repr() do str do Python: aspas simples, ou duplas quando so ha simples no texto. */
    static String repr(String s) {
        char aspa = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder b = new StringBuilder().append(aspa);
        s.codePoints().forEach(c -> {
            if (c == aspa || c == '\\') b.append('\\').appendCodePoint(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20 || c == 0x7f) b.append(String.format("\\x%02x", c));
            else b.appendCodePoint(c);
        });
        return b.append(aspa).toString();
    }
}
