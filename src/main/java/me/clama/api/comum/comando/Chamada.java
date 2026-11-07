package me.clama.api.comum.comando;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * O que o comando recebe. opcoes: chave = "--flag" (valor; flag booleana = "true") ou o nome do posicional; opcao
 * ausente nao tem chave. out e o stdout do relatorio (UTF-8, nunca log); err, o stderr; in, o stdin (UTF-8).
 * diretorio: o de trabalho, contra o qual caminho relativo resolve (no jar, o cwd; no teste, o dir de saida).
 */
public record Chamada(Map<String, String> opcoes, PrintStream out, PrintStream err, BufferedReader in, Path diretorio) {
    public String valor(String nome) {
        return opcoes.get(nome);
    }

    public boolean tem(String nome) {
        return opcoes.containsKey(nome);
    }

    public Path caminho(String caminho) {
        return diretorio.resolve(caminho);
    }
}
