package me.clama.api.identidade.api;

import jakarta.servlet.http.HttpServletResponse;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.api.UsuarioDtos.AdminUser;
import me.clama.api.identidade.api.UsuarioDtos.Login;
import me.clama.api.identidade.http.CookiesDeAuth;
import me.clama.api.identidade.token.ParDeTokens;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** AdminLoginView. O refresh do admin e igual ao do customer e fica no AuthCustomerController. */
@RestController
class AuthAdminController {
    private final Autenticacao autenticacao;
    private final ServicoDeTokens tokens;
    private final CookiesDeAuth cookies;
    private final TransactionTemplate tx;

    AuthAdminController(Autenticacao autenticacao, ServicoDeTokens tokens, CookiesDeAuth cookies,
            TransactionTemplate tx) {
        this.autenticacao = autenticacao;
        this.tokens = tokens;
        this.cookies = cookies;
        this.tx = tx;
    }

    record Sessao(Usuario usuario, ParDeTokens par) {}

    /** Lookup de e-mail exato (so o login do customer e iexact). Cookies so depois do commit. */
    @PostMapping("/api/admin/auth/login/")
    @LimiteDeTaxa(escopo = "admin_login", limite = 5, janelaSegundos = 60)
    Login<AdminUser> login(@RequestBody(required = false) JsonNode corpo, HttpServletResponse resp) {
        CamposDrf campos = new CamposDrf(corpo);
        String email = campos.texto("email");
        String senha = campos.texto("password");
        campos.validar();
        Sessao s = tx.execute(st -> {
            Usuario u = autenticacao.autenticar(email, senha);
            if (u == null) {
                throw PastoralException.naoAutenticado();
            }
            if (!u.isClamaAdmin()) {
                throw new PastoralException(400, "not_admin", "User is not a Clama admin",
                        "Esse espaço é só para admins do Clama.");
            }
            return new Sessao(u, tokens.emitirPar(u));
        });
        cookies.gravar(resp, s.par());
        return new Login<>(AdminUser.de(s.usuario()));
    }
}
