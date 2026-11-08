package me.clama.api.instituicao.repasse.comando;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** csv.writer(delimiter=";") num open("w", newline="", encoding="utf-8-sig") do Python (mapa §1.7). */
class PlanilhaCsvTest {
    @Test
    void quoteMinimalComPontoEVirgulaCrlfEBom(@TempDir Path dir) throws Exception {
        // python3 -c "import csv,io; b=io.StringIO(); w=csv.writer(b,delimiter=';');
        //   [w.writerow(r) for r in [[],[''],[' '],['a b',' x '],['a;b'],['a\"b'],['a\rb'],['a\nb'],['',''],['\t'],
        //   ['é—…']]]; print(repr(b.getvalue()))"
        String python = "\r\n\"\"\r\n \r\na b; x \r\n\"a;b\"\r\n\"a\"\"b\"\r\n\"a\rb\"\r\n\"a\nb\"\r\n;\r\n\t\r\né—…\r\n";
        PlanilhaCsv p = new PlanilhaCsv().linha().linha("").linha(" ").linha("a b", " x ").linha("a;b").linha("a\"b")
                .linha("a\rb").linha("a\nb").linha("", "").linha("\t").linha("é—…");
        Path destino = dir.resolve("sub/pasta/x.csv"); // cria a pasta, como o mkdir(parents=True)
        p.gravar(destino);
        byte[] b = Files.readAllBytes(destino);
        assertThat(b).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(b, 3, b.length - 3, UTF_8)).isEqualTo(python);

        Files.writeString(destino, "conteudo antigo bem mais comprido que o novo");
        new PlanilhaCsv().linha("novo").gravar(destino); // trunca
        assertThat(Files.readString(destino, UTF_8)).isEqualTo("﻿novo\r\n");
    }
}
