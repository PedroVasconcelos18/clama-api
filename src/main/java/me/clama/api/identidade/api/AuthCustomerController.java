package me.clama.api.identidade.api;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.api.AuthAdminController.Sessao;
import me.clama.api.identidade.api.UsuarioDtos.CustomerUser;
import me.clama.api.identidade.api.UsuarioDtos.Login;
import me.clama.api.identidade.http.CookiesDeAuth;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.identidade.senha.ValidadorDeSenha;
import me.clama.api.identidade.token.JwtInvalido;
import me.clama.api.identidade.token.ParDeTokens;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Views de clama_backend/users/api/views.py (as de clama/customers/api sao codigo morto). */
@RestController
class AuthCustomerController {
    private static final Set<String> FORMATOS_DE_NOME = Set.of("completo", "compacto");

    private final Autenticacao autenticacao;
    private final ServicoDeTokens tokens;
    private final CookiesDeAuth cookies;
    private final TransactionTemplate tx;
    private final UsuarioRepository usuarios;
    private final SenhaDjango senhas;
    private final ValidadorDeSenha validador;

    AuthCustomerController(Autenticacao autenticacao, ServicoDeTokens tokens, CookiesDeAuth cookies,
            TransactionTemplate tx, UsuarioRepository usuarios, SenhaDjango senhas, ValidadorDeSenha validador) {
        this.autenticacao = autenticacao;
        this.tokens = tokens;
        this.cookies = cookies;
        this.tx = tx;
        this.usuarios = usuarios;
        this.senhas = senhas;
        this.validador = validador;
    }

    /** Admin, inativo, senha errada, e-mail ausente ou ambiguo: a mesma 401, sem revelar o motivo. */
    @PostMapping("/api/customer/auth/login/")
    @LimiteDeTaxa(escopo = "customer_login", limite = 5, janelaSegundos = 60)
    Login<CustomerUser> login(@RequestBody(required = false) JsonNode corpo, HttpServletResponse resp) {
        CamposDrf campos = new CamposDrf(corpo);
        String email = campos.texto("email");
        String senha = campos.texto("password");
        campos.validar();
        Sessao s = tx.execute(st -> {
            Usuario u = autenticacao.autenticarIgnorandoCaixa(email, senha);
            if (u == null || u.isClamaAdmin()) {
                throw new PastoralException(401, "invalid_credentials", "Email or password invalid",
                        "Email ou senha inválidos.");
            }
            return new Sessao(u, tokens.emitirPar(u));
        });
        cookies.gravar(resp, s.par());
        return new Login<>(CustomerUser.de(s.usuario()));
    }

    /** CustomerTokenRefreshView e AdminTokenRefreshView sao identicas: refresh do cookie, rotacao, corpo {}. */
    @PostMapping({"/api/customer/auth/refresh/", "/api/admin/auth/refresh/"})
    Map<String, Object> refresh(@CookieValue(name = CookiesDeAuth.REFRESH, required = false) String refresh,
            HttpServletResponse resp) {
        if (refresh == null || refresh.isEmpty()) {
            throw new PastoralException(401, "refresh_cookie_ausente", "Refresh cookie missing",
                    "Sua sessão expirou. Faça login pra continuar.");
        }
        ParDeTokens par;
        try {
            par = tokens.rotacionar(refresh);
        } catch (JwtInvalido e) {
            throw PastoralException.naoAutenticado(); // InvalidToken do simplejwt
        }
        cookies.gravar(resp, par);
        return Map.of();
    }

    /** Idempotente: sem refresh, ou refresh ja invalido/revogado, tambem da 205. So o refresh de outro da 400. */
    @PostMapping("/api/customer/auth/logout/")
    ResponseEntity<Void> logout(@AuthenticationPrincipal Usuario eu,
            @CookieValue(name = CookiesDeAuth.REFRESH, required = false) String refresh, HttpServletResponse resp) {
        if (refresh != null && !refresh.isEmpty()) {
            boolean doUsuario;
            try {
                doUsuario = tokens.revogarDoUsuario(refresh, eu.getId());
            } catch (JwtInvalido e) {
                doUsuario = true;
            }
            if (!doUsuario) {
                throw new PastoralException(400, "refresh_invalid", "Refresh token does not belong to current session",
                        "Token inválido pra esta sessão.");
            }
        }
        cookies.apagar(resp);
        return ResponseEntity.status(205).build();
    }

    /** Trava a linha (select_for_update) e confere a senha atual contra ela; sucesso revoga todos os refresh. */
    @PostMapping("/api/customer/auth/change-password/")
    @LimiteDeTaxa(escopo = "customer_change_password", limite = 10, janelaSegundos = 3600)
    Map<String, String> trocarSenha(@AuthenticationPrincipal Usuario eu,
            @RequestBody(required = false) JsonNode corpo) {
        CamposDrf campos = new CamposDrf(corpo);
        String atual = campos.texto("senha_atual");
        // validators=[validate_password] sem user: o de similaridade com o e-mail nao roda aqui
        String nova = campos.texto("nova_senha", s -> validador.validar(s, null));
        campos.validar();
        tx.executeWithoutResult(st -> {
            Usuario u = usuarios.travar(eu.getId()).orElseThrow();
            if (!senhas.matches(atual, u.getPassword())) {
                throw new PastoralException(400, "senha_atual_invalida", "Current password mismatch",
                        "A senha atual está incorreta.");
            }
            u.setPassword(senhas.encode(nova));
            u.setForceChangePassword(false);
            tokens.revogarTodos(u.getId());
        });
        var resposta = new java.util.LinkedHashMap<String, String>();
        resposta.put("detail", "Senha atualizada com sucesso.");
        resposta.put("pastoral_message", "Senha atualizada — você já pode seguir em paz.");
        return resposta;
    }

    @GetMapping("/api/customer/me/")
    CustomerUser me(@AuthenticationPrincipal Usuario eu) {
        return CustomerUser.de(eu);
    }

    /** So nome_format_blog e gravavel; os outros campos sao ignorados em silencio (read_only_fields). */
    @PatchMapping("/api/customer/me/")
    CustomerUser atualizar(@AuthenticationPrincipal Usuario eu, @RequestBody(required = false) JsonNode corpo) {
        CamposDrf campos = new CamposDrf(corpo);
        JsonNode v = campos.get("nome_format_blog");
        String formato = null;
        if (v != null && v.isNull()) {
            campos.erro("nome_format_blog", CamposDrf.NULO);
        } else if (v != null && !(v.isString() && FORMATOS_DE_NOME.contains(v.asString()))) {
            campos.erro("nome_format_blog", "\"" + TextoPython.str(v) + "\" não é uma escolha válida.");
        } else if (v != null) {
            formato = v.asString();
        }
        campos.validar();
        String novo = formato;
        return tx.execute(st -> {
            Usuario u = usuarios.findById(eu.getId()).orElseThrow();
            if (novo != null) {
                u.setNomeFormatBlog(novo);
            }
            return CustomerUser.de(u);
        });
    }
}
