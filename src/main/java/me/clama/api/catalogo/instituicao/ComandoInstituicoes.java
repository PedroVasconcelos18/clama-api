package me.clama.api.catalogo.instituicao;

import java.util.List;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import org.springframework.stereotype.Component;

/**
 * instituicoes (mapa §9.6): uma linha "&lt;uuid&gt;\t&lt;nome&gt;" por instituicao, ativa ou nao, em ordem, nome. Substitui o
 * "manage.py shell -c ...count()" do script mensal, que conta as linhas (wc -l).
 */
@Component
public class ComandoInstituicoes implements Comando {
    public static final List<Opcao> OPCOES = List.of();

    private final InstituicaoRepository instituicoes;

    ComandoInstituicoes(InstituicaoRepository instituicoes) {
        this.instituicoes = instituicoes;
    }

    @Override
    public int executar(Chamada c) {
        instituicoes.findAllByOrderByOrdemAscNomeAsc().forEach(i -> c.out().println(i.getId() + "\t" + i.getNome()));
        return 0;
    }
}
