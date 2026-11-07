package me.clama.api.comum.comando;

/**
 * Um comando do jar (management command do Django): bean comum, registrado com suas opcoes em Comandos.DEFINICOES.
 * Erro esperado = ErroDeComando (ou PastoralException); o resto vira traceback, Sentry e exit 1.
 */
public interface Comando {
    /** Devolve o exit code (0 ok). */
    int executar(Chamada chamada) throws Exception;
}
