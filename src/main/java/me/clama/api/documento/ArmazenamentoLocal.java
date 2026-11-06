package me.clama.api.documento;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;
import me.clama.api.comum.texto.TextoPython;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FileSystemStorage do Django sob clama.media-root (MEDIA_ROOT). Como no Django, o disco do Railway e efemero:
 * o arquivo some no redeploy (so importa para sincronizar com a Anthropic depois).
 */
@Component
public class ArmazenamentoLocal {
    /** max_length do FileField (coluna arquivo varchar(100)). */
    static final int MAX_NOME = 100;
    private static final String ALFANUMERICO = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final Path raiz;

    public ArmazenamentoLocal(@Value("${clama.media-root:./media}") String raiz) {
        this.raiz = Path.of(raiz).toAbsolutePath().normalize();
    }

    /**
     * FieldFile.save: upload_to (documents/<id>/<nome>) + get_valid_name + get_available_name (sufixo de 7 caracteres
     * na colisao, raiz do nome truncada para caber em 100) e criacao exclusiva (O_EXCL). Devolve o nome relativo,
     * que vai na coluna arquivo.
     */
    public String salvar(UUID id, String nomeEnviado, InputStream conteudo) {
        String pasta = "documents/" + id;
        String valido = nomeValido(nomeEnviado);
        int ponto = pontoDaExtensao(valido);
        String raizDoNome = ponto < 0 ? valido : valido.substring(0, ponto);
        String extensao = ponto < 0 ? "" : valido.substring(ponto);
        String nome = pasta + "/" + valido;
        try {
            Files.createDirectories(caminho(pasta));
            while (true) {
                while (Files.exists(caminho(nome)) || tamanho(nome) > MAX_NOME) {
                    nome = pasta + "/" + alternativo(raizDoNome, extensao);
                    int excesso = tamanho(nome) - MAX_NOME;
                    if (excesso > 0) {
                        int resta = tamanho(raizDoNome) - excesso;
                        if (resta <= 0) {
                            throw new IllegalArgumentException("Storage can not find an available filename for \""
                                    + nome + "\".");
                        }
                        raizDoNome = raizDoNome.substring(0, raizDoNome.offsetByCodePoints(0, resta));
                        nome = pasta + "/" + alternativo(raizDoNome, extensao);
                    }
                }
                try {
                    Files.copy(conteudo, caminho(nome)); // sem REPLACE_EXISTING: CREATE_NEW
                    return nome;
                } catch (FileAlreadyExistsException corrida) {
                    // outro upload pegou o nome entre o exists e o copy: tenta outro, como o _save do Django
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Arquivo de um nome relativo; nome que sairia de clama.media-root e recusado. */
    public Path caminho(String relativo) {
        Path p = raiz.resolve(relativo).normalize();
        if (Path.of(relativo).isAbsolute() || !p.startsWith(raiz) || p.equals(raiz)) {
            throw new IllegalArgumentException("Detected path traversal attempt in '" + relativo + "'");
        }
        return p;
    }

    /** get_valid_filename: strip, espaco vira _, so [-\w.] (\w unicode do Python). */
    static String nomeValido(String nome) {
        StringBuilder b = new StringBuilder();
        TextoPython.strip(nome).replace(' ', '_').codePoints()
                .filter(c -> c == '-' || c == '.' || c == '_' || palavra(c)).forEach(b::appendCodePoint);
        String s = b.toString();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            throw new IllegalArgumentException("Could not derive file name from '" + nome + "'");
        }
        return s;
    }

    /** \w do re do Python para str: str.isalnum() (letras L*, numeros N*). Marcas combinantes (NFD) nao entram. */
    private static boolean palavra(int c) {
        return switch (Character.getType(c)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER,
                    Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
            default -> false;
        };
    }

    /** os.path.splitext: o ponto da extensao, ignorando pontos iniciais; -1 se nao houver. */
    static int pontoDaExtensao(String nome) {
        int i = nome.lastIndexOf('.');
        for (int k = 0; k < i; k++) {
            if (nome.charAt(k) != '.') {
                return i;
            }
        }
        return -1;
    }

    /** get_alternative_name: raiz_<get_random_string(7)>ext. */
    private static String alternativo(String raizDoNome, String extensao) {
        StringBuilder b = new StringBuilder(raizDoNome).append('_');
        for (int i = 0; i < 7; i++) {
            b.append(ALFANUMERICO.charAt(ALEATORIO.nextInt(ALFANUMERICO.length())));
        }
        return b.append(extensao).toString();
    }

    /** len() do Python. */
    private static int tamanho(String s) {
        return s.codePointCount(0, s.length());
    }
}
