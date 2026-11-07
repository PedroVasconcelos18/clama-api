package me.clama.api.blog;

import java.util.List;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.identidade.Usuario;

/** _autor_nome_publico (serializers.py:22-43): nunca expoe e-mail; vazio vira "Pedro". */
public final class NomePublico {
    private NomePublico() {}

    public static String de(Usuario u) {
        String nome = u == null || u.getNomeCompleto() == null ? "" : TextoPython.strip(u.getNomeCompleto());
        if (nome.isEmpty()) {
            return "Pedro";
        }
        if ("completo".equals(u.getNomeFormatBlog())) {
            return nome;
        }
        List<String> partes = TextoPython.split(nome);
        if (partes.size() == 1) {
            return partes.get(0);
        }
        String ultima = partes.getLast();
        return partes.get(0) + " " + ultima.substring(0, ultima.offsetByCodePoints(0, 1)) + ".";
    }
}
