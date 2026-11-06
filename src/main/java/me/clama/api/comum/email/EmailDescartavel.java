package me.clama.api.comum.email;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import me.clama.api.comum.texto.TextoPython;

/** clama/freemium/services/email_blacklist.py: o dominio e cada sufixo ancestral (nunca o TLD sozinho) contra a lista estatica. */
public final class EmailDescartavel {
    private EmailDescartavel() {}

    private static final Set<String> DOMINIOS = carregar();

    private static Set<String> carregar() {
        try (var in = EmailDescartavel.class.getResourceAsStream("/disposable_email_domains.txt")) {
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int tamanho() {
        return DOMINIOS.size();
    }

    public static boolean eh(String email) {
        if (email == null) return false;
        String norm = TextoPython.strip(email).toLowerCase(Locale.ROOT);
        int arroba = norm.lastIndexOf('@');
        if (arroba < 0) return false;
        String dominio = TextoPython.strip(norm.substring(arroba + 1));
        if (dominio.isEmpty()) return false;
        String[] partes = dominio.split("\\.", -1);
        for (int i = 0; i < partes.length - 1; i++) {
            if (DOMINIOS.contains(String.join(".", java.util.Arrays.copyOfRange(partes, i, partes.length)))) return true;
        }
        return false;
    }
}
