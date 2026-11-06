package me.clama.api;

import java.nio.file.Path;
import org.springframework.util.FileSystemUtils;

/** Limpeza do MEDIA_ROOT dos testes: recusa qualquer caminho fora de target/ (nunca apagar ./media de verdade). */
public final class MidiaDeTeste {
    private MidiaDeTeste() {}

    public static void apagar(Path media) {
        Path alvo = media.toAbsolutePath().normalize();
        Path target = Path.of("target").toAbsolutePath().normalize();
        if (!alvo.startsWith(target) || alvo.equals(target)) {
            throw new IllegalArgumentException("recuso apagar fora de target/: " + alvo);
        }
        FileSystemUtils.deleteRecursively(alvo.toFile());
    }
}
