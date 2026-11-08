package me.clama.api.instituicao.repasse.comando;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * csv.writer(delimiter=";") num open("w", newline="", encoding="utf-8-sig") (mapa §1.7): BOM uma vez, QUOTE_MINIMAL
 * (cita o campo com ; " \r ou \n, e o campo unico vazio; " vira ""), linha terminada em \r\n.
 */
final class PlanilhaCsv {
    private final StringBuilder texto = new StringBuilder("﻿");

    PlanilhaCsv linha(String... celulas) {
        for (int i = 0; i < celulas.length; i++) {
            String c = celulas[i];
            if (i > 0) {
                texto.append(';');
            }
            if (c.chars().anyMatch(ch -> ch == ';' || ch == '"' || ch == '\r' || ch == '\n')
                    || (c.isEmpty() && celulas.length == 1)) {
                texto.append('"').append(c.replace("\"", "\"\"")).append('"');
            } else {
                texto.append(c);
            }
        }
        texto.append("\r\n");
        return this;
    }

    /** destino.parent.mkdir(parents=True, exist_ok=True) e grava (trunca o que houver). */
    void gravar(Path destino) throws IOException {
        Path pasta = destino.toAbsolutePath().getParent();
        if (!Files.isDirectory(pasta)) {
            Files.createDirectories(pasta);
        }
        Files.writeString(destino, texto, UTF_8);
    }
}
