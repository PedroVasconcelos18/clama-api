package me.clama.api.comum.email.modelo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Subconjunto do template engine do Django usado nos e-mails: {{ var }} (com autoescape, tambem nos .txt) e
 * {% if a and b %}...{% endif %} sem aninhamento. Qualquer outra tag ou filtro e erro, pra um template novo
 * nunca sair diferente do Django em silencio.
 */
@Component
public class RenderizadorDeTemplate {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{(.*?)\\}\\}|\\{%(.*?)%\\}", Pattern.DOTALL);
    private static final Pattern VAR = Pattern.compile("\\w+");
    private static final Pattern IF = Pattern.compile("if (\\w+) and (\\w+)");

    /** Le {@code caminho} (ex.: "email/oracao.html") do classpath, em UTF-8. */
    public String renderizar(String caminho, Map<String, String> contexto) {
        try (var in = new ClassPathResource(caminho).getInputStream()) {
            return renderizarTexto(new String(in.readAllBytes(), StandardCharsets.UTF_8), contexto);
        } catch (IOException e) {
            throw new UncheckedIOException(caminho, e);
        }
    }

    String renderizarTexto(String t, Map<String, String> ctx) {
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(t);
        int pos = 0;
        boolean dentroDoIf = false;
        boolean ativo = true;
        while (m.find()) {
            if (ativo) {
                out.append(t, pos, m.start());
            }
            pos = m.end();
            if (m.group(1) != null) {
                String nome = m.group(1).strip();
                if (!VAR.matcher(nome).matches()) {
                    throw new IllegalArgumentException("variavel/filtro nao suportado: {{" + m.group(1) + "}}");
                }
                if (ativo) {
                    out.append(escapar(ctx.getOrDefault(nome, "")));
                }
                continue;
            }
            String tag = m.group(2).strip();
            Matcher mi = IF.matcher(tag);
            if (mi.matches() && !dentroDoIf) {
                dentroDoIf = true;
                ativo = !ctx.getOrDefault(mi.group(1), "").isEmpty() && !ctx.getOrDefault(mi.group(2), "").isEmpty();
            } else if (tag.equals("endif") && dentroDoIf) {
                dentroDoIf = false;
                ativo = true;
            } else {
                throw new IllegalArgumentException("tag nao suportada: {%" + m.group(2) + "%}");
            }
        }
        if (dentroDoIf) {
            throw new IllegalArgumentException("{% if %} sem {% endif %}");
        }
        return out.append(t, pos, t.length()).toString();
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#x27;");
    }
}
