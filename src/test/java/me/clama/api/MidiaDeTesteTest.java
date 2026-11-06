package me.clama.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MidiaDeTesteTest {
    @Test
    void recusaForaDeTarget() {
        for (String p : new String[] {"./media", "media", "/tmp", "target", "target/../media", "."}) {
            assertThatThrownBy(() -> MidiaDeTeste.apagar(Path.of(p))).as(p).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void apagaSobTarget() throws Exception {
        Path d = Path.of("target/media-guarda-test/a");
        Files.createDirectories(d);
        MidiaDeTeste.apagar(Path.of("target/media-guarda-test"));
        assertThat(Path.of("target/media-guarda-test")).doesNotExist();
    }
}
