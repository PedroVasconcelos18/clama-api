package me.clama.api.identidade.comando;

import java.io.Console;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import me.clama.api.comum.auditoria.RegistroDeAuditoria;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.comando.ErroDeComando;
import me.clama.api.comum.email.ValidadorDeEmail;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.identidade.CriacaoDeAdmin;
import me.clama.api.identidade.senha.ValidadorDeSenha;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * create_clama_admin (mapa §6, D7, D15): o mesmo CriacaoDeAdmin da tela. Correcoes sobre o Django: o duplicado e
 * canonico (caixa e Gmail), a senha passa pelo ValidadorDeSenha, e --password e opcional.
 * <p>
 * Sem --password, a senha e pedida sem eco: com terminal, duas vezes por System.console().readPassword(); sem terminal
 * (pipe, redirecionamento), uma linha do stdin recebido, onde nao ha eco a esconder (use so em script confiavel; a
 * senha na linha de comando fica no historico e no ps). --superusuario e o resgate quando se perde o unico superusuario.
 * --autor audita no django_admin_log (que exige autor); sem ele, so o log estruturado, sem e-mail nem senha.
 */
@Component
public class CreateClamaAdmin implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.obrigatoria("--email"), Opcao.valor("--password"),
            Opcao.valor("--nome"), Opcao.flag("--superusuario"), Opcao.valor("--autor"));
    private static final Logger LOG = LoggerFactory.getLogger(CreateClamaAdmin.class);

    private final CriacaoDeAdmin criacao;
    private final ValidadorDeSenha validador;
    private final RegistroDeAuditoria auditoria;

    CreateClamaAdmin(CriacaoDeAdmin criacao, ValidadorDeSenha validador, RegistroDeAuditoria auditoria) {
        this.criacao = criacao;
        this.validador = validador;
        this.auditoria = auditoria;
    }

    @Override
    public int executar(Chamada c) throws IOException {
        String email = TextoPython.strip(c.valor("--email")).toLowerCase(Locale.ROOT);
        String nome = c.valor("--nome") == null ? "" : c.valor("--nome");
        if (!ValidadorDeEmail.valido(email)) {
            throw new ErroDeComando("E-mail inválido: informe um endereço como nome@dominio.com.");
        }
        boolean superusuario = c.tem("--superusuario");
        Long autor = auditoria.autorDoComando(c.valor("--autor"));
        String senha = c.valor("--password") != null ? c.valor("--password") : pedirSenha(c);

        List<String> erros = validador.validar(senha, email);
        if (!erros.isEmpty()) {
            throw new ErroDeComando(String.join(" ", erros));
        }
        long id;
        try {
            id = criacao.criar(email, nome, senha, superusuario, autor);
        } catch (CriacaoDeAdmin.EmailJaExiste e) {
            throw new ErroDeComando("Usuário com email " + email + " já existe.");
        }
        LOG.info("admin_criado user_id={} superusuario={} auditado={}", id, superusuario, autor != null);
        c.out().println("Admin criado com sucesso: " + email);
        return 0;
    }

    private String pedirSenha(Chamada c) throws IOException {
        Console console = System.console();
        if (console != null && console.isTerminal()) {
            char[] a = console.readPassword("Senha: ");
            char[] b = console.readPassword("Senha (de novo): ");
            if (a == null || b == null || !java.util.Arrays.equals(a, b)) {
                throw new ErroDeComando("As senhas não conferem. Nada foi criado.");
            }
            return new String(a);
        }
        c.err().println("Sem terminal: lendo a senha de uma linha do stdin (sem eco).");
        String linha = c.in().readLine();
        if (linha == null) {
            throw new ErroDeComando("Senha não informada: use --password ou envie uma linha no stdin.");
        }
        return linha;
    }
}
