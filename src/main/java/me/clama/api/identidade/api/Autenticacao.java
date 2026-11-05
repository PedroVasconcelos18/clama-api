package me.clama.api.identidade.api;

import java.util.List;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import org.springframework.stereotype.Component;

/** authenticate() do Django com o ModelBackend. Rodar dentro da transacao da requisicao (pode regravar o hash). */
@Component
class Autenticacao {
    private final UsuarioRepository usuarios;
    private final SenhaDjango senhas;

    Autenticacao(UsuarioRepository usuarios, SenhaDjango senhas) {
        this.usuarios = usuarios;
        this.senhas = senhas;
    }

    /** Lookup exato do e-mail (get_by_natural_key); null se nao achar, senha errada ou inativo. */
    Usuario autenticar(String email, String senha) {
        return autenticar(usuarios.buscarPorEmailIgnorandoCaixa(email), email, senha);
    }

    /**
     * CustomerTokenObtainPairSerializer: resolve o e-mail por iexact quando ha um so candidato; ambiguo ou ausente
     * segue com o e-mail digitado, e o ModelBackend faz o lookup exato.
     */
    Usuario autenticarIgnorandoCaixa(String email, String senha) {
        List<Usuario> candidatos = usuarios.buscarPorEmailIgnorandoCaixa(email);
        String resolvido = candidatos.size() == 1 ? candidatos.getFirst().getEmail() : email;
        return autenticar(candidatos, resolvido, senha);
    }

    private Usuario autenticar(List<Usuario> candidatos, String email, String senha) {
        Usuario u = candidatos.stream().filter(c -> c.getEmail().equals(email)).findFirst().orElse(null);
        if (u == null) {
            senhas.encode(senha); // o Django gasta um hash pra nao revelar por tempo que o e-mail nao existe
            return null;
        }
        // check_password: confere e, se o hash estiver desatualizado, regrava (antes de olhar is_active)
        if (!senhas.matches(senha, u.getPassword())) {
            return null;
        }
        if (senhas.upgradeEncoding(u.getPassword())) {
            u.setPassword(senhas.encode(senha));
            usuarios.save(u);
        }
        return u.isActive() ? u : null;
    }
}
