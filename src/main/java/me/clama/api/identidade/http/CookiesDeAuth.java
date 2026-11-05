package me.clama.api.identidade.http;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import me.clama.api.identidade.token.ParDeTokens;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * set_auth_cookies / clear_auth_cookies do Django. Path=/api impede o WordPress (/blog) de receber o token.
 * Gravar so no fim do caminho feliz.
 */
@Component
public class CookiesDeAuth {
    static final String ACCESS = "clama_access";
    static final String REFRESH = "clama_refresh";
    private static final String PATH = "/api";

    private final boolean secure;

    public CookiesDeAuth(@Value("${AUTH_COOKIE_SECURE:true}") boolean secure) {
        this.secure = secure;
    }

    public void gravar(HttpServletResponse resp, ParDeTokens par) {
        resp.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS, par.access(), ServicoDeTokens.ACCESS));
        resp.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH, par.refresh(), ServicoDeTokens.REFRESH));
    }

    /** delete_cookie do Django: so path e samesite (sem HttpOnly/Secure), Max-Age=0. */
    public void apagar(HttpServletResponse resp) {
        for (String nome : new String[] {ACCESS, REFRESH}) {
            resp.addHeader(HttpHeaders.SET_COOKIE,
                    ResponseCookie.from(nome, "").path(PATH).maxAge(0).sameSite("Lax").build().toString());
        }
    }

    private String cookie(String nome, String valor, Duration vida) {
        return ResponseCookie.from(nome, valor).path(PATH).maxAge(vida).secure(secure).httpOnly(true).sameSite("Lax")
                .build().toString();
    }
}
