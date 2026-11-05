package me.clama.api.identidade.senha;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import me.clama.api.comum.texto.TextoPython;

/** Os 4 validadores do AUTH_PASSWORD_VALIDATORS do Django, na mesma ordem e com as mesmas mensagens pt-BR. */
public class ValidadorDeSenha {
    // O Django interpola o verbose_name do campo email ("E-mail"); texto copiado da saida real.
    static final String PARECIDA = "A senha é muito parecida com E-mail";
    static final String CURTA = "Esta senha é muito curta. Ela precisa conter pelo menos 8 caracteres.";
    static final String COMUM = "Esta senha é muito comum.";
    static final String NUMERICA = "Esta senha é inteiramente numérica.";

    private static final double MAX_SIMILARIDADE = 0.7;
    private static final Pattern NAO_PALAVRA = Pattern.compile("\\W+", Pattern.UNICODE_CHARACTER_CLASS);
    private final Set<String> comuns = carregarComuns();

    private static Set<String> carregarComuns() {
        try (var in = ValidadorDeSenha.class.getResourceAsStream("/common-passwords.txt.gz");
                var r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            Set<String> s = new HashSet<>();
            r.lines().forEach(l -> s.add(TextoPython.strip(l)));
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> validar(String senha, String email) {
        List<String> erros = new ArrayList<>();
        if (parecida(senha, email)) {
            erros.add(PARECIDA);
        }
        if (senha.codePointCount(0, senha.length()) < 8) {
            erros.add(CURTA);
        }
        if (comuns.contains(TextoPython.strip(senha.toLowerCase(Locale.ROOT)))) {
            erros.add(COMUM);
        }
        if (!senha.isEmpty() && senha.codePoints().allMatch(TextoPython::isDigit)) {
            erros.add(NUMERICA);
        }
        return erros;
    }

    private static boolean parecida(String senha, String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        String a = senha.toLowerCase(Locale.ROOT);
        String valor = email.toLowerCase(Locale.ROOT);
        List<String> partes = new ArrayList<>(List.of(NAO_PALAVRA.split(valor, -1)));
        partes.add(valor);
        for (String parte : partes) {
            int la = a.codePointCount(0, a.length()), lb = parte.codePointCount(0, parte.length());
            if (la >= 10 * lb && lb < MAX_SIMILARIDADE / 2 * la) { // exceeds_maximum_length_ratio
                continue;
            }
            if (quickRatio(a, parte) >= MAX_SIMILARIDADE) {
                return true;
            }
        }
        return false;
    }

    /** difflib.SequenceMatcher.quick_ratio: interseccao de multiconjuntos. O Django 4.2 so usa este, nunca ratio(). */
    private static double quickRatio(String a, String b) {
        int total = a.codePointCount(0, a.length()) + b.codePointCount(0, b.length());
        if (total == 0) {
            return 1.0;
        }
        Map<Integer, Integer> disp = new HashMap<>();
        b.codePoints().forEach(c -> disp.merge(c, 1, Integer::sum));
        int[] iguais = {0};
        a.codePoints().forEach(c -> {
            if (disp.merge(c, -1, Integer::sum) >= 0) {
                iguais[0]++;
            }
        });
        return 2.0 * iguais[0] / total;
    }
}
