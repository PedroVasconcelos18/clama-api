package me.clama.blog.interno;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moderacao automatica (moderation.py): eh_comentario_suspeito. A lista e a do codigo do Django, 36 entradas (o mapa
 * diz 40, mas o frozenset tem 36).
 */
final class Moderacao {
    private Moderacao() {}

    static final List<String> PALAVRAS_SUSPEITAS = List.of(
            "buceta",
            "caralho",
            "porra",
            "merda",
            "cuzao",
            "vagabundo",
            "vagabunda",
            "fdp",
            "puta",
            "putinha",
            "puto",
            "filhodaputa",
            "filhadaputa",
            "cu",
            "viado",
            "bicha",
            "babaca",
            "otario",
            "otaria",
            "idiota",
            "imbecil",
            "satanico",
            "satanica",
            "blasfemia",
            "herege",
            "demonio",
            "compre agora",
            "curso gratis",
            "curso grátis",
            "ganhe dinheiro",
            "ganhe agora",
            "click aqui",
            "clique aqui",
            "investimento garantido",
            "renda extra",
            "trabalhe em casa");

    private static final Pattern PALAVRA =
            Pattern.compile("[a-z\u00e0-\u00ff0-9]+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> TERMOS = new HashSet<>();
    private static final List<String> FRASES;

    static {
        List<String> frases = new ArrayList<>();
        for (String t : PALAVRAS_SUSPEITAS) {
            String n = normalizar(t);
            if (n.contains(" ")) {
                frases.add(n);
            } else {
                TERMOS.add(n);
            }
        }
        FRASES = List.copyOf(frases);
    }

    /** lower() + NFD sem as marcas combinantes (categoria Mn). */
    private static String normalizar(String texto) {
        String nfd = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        nfd.codePoints().filter(c -> Character.getType(c) != Character.NON_SPACING_MARK).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** Termo de uma palavra: token inteiro igual; termo com espaco: substring do texto normalizado. */
    static boolean ehSuspeito(String conteudo) {
        if (conteudo == null || conteudo.isEmpty()) {
            return false;
        }
        String normalizado = normalizar(conteudo);
        Matcher m = PALAVRA.matcher(normalizado);
        while (m.find()) {
            if (TERMOS.contains(m.group())) {
                return true;
            }
        }
        return FRASES.stream().anyMatch(normalizado::contains);
    }
}
