package me.clama.api.contrato;

import java.nio.file.Path;

/**
 * O modo comando do jar como o replay o chama, nos cenarios "comando" de contrato/comandos (etapa 8). A implementacao
 * de verdade (Task 2) roda o despacho de comandos em processo, num contexto de teste ja aberto.
 */
@FunctionalInterface
public interface ExecutorDeComando {
    /** stdout e stderr como o comando os escreveu; exit = o codigo com que o jar sairia. */
    record Resultado(String stdout, String stderr, int exit) {}

    /**
     * args: o nome do comando e os argumentos, como na linha de comando do jar (java -jar clama-api.jar args...).
     * stdin: o que o comando le (o prompt do purgar_dados_blog_customer), ou null (EOF na primeira leitura).
     * dirSaida: o diretorio de trabalho do comando. Arquivo gravado com caminho relativo (o nome padrao do
     * relatorio_repasse) cai nele, e o replay compara todo arquivo que ficar ali.
     */
    Resultado executar(String[] args, String stdin, Path dirSaida) throws Exception;
}
