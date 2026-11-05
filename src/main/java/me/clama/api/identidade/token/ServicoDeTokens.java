package me.clama.api.identidade.token;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mesma semantica do simplejwt 5.5 (ROTATE + BLACKLIST_AFTER_ROTATION, user_id como string). */
@Service
public class ServicoDeTokens {
    public static final Duration ACCESS = Duration.ofMinutes(15);
    public static final Duration REFRESH = Duration.ofDays(7);

    private final Jwt jwt;
    private final TokenEmitidoRepository emitidos;
    private final TokenRevogadoRepository revogados;
    private final UsuarioRepository usuarios;

    public ServicoDeTokens(Jwt jwt, TokenEmitidoRepository emitidos, TokenRevogadoRepository revogados, UsuarioRepository usuarios) {
        this.jwt = jwt;
        this.emitidos = emitidos;
        this.revogados = revogados;
        this.usuarios = usuarios;
    }

    @Transactional
    public ParDeTokens emitirPar(Usuario u) {
        return novoPar(String.valueOf(u.getId()));
    }

    @Transactional
    public ParDeTokens rotacionar(String refresh) {
        Map<String, Object> c = verificarRefresh(refresh);
        Usuario u = usuarioAtivo(c);
        revogar(refresh, c);
        return novoPar(String.valueOf(u.getId()));
    }

    @Transactional
    public void revogar(String refresh) {
        revogar(refresh, verificarRefresh(refresh));
    }

    /**
     * Logout (P-4 da view do Django): so revoga se o refresh for do usuario logado; false se for de outro.
     * Compara como string, igual ao str(token_user_id) != str(request.user.pk) do Django.
     */
    @Transactional
    public boolean revogarDoUsuario(String refresh, Long usuarioId) {
        Map<String, Object> c = verificarRefresh(refresh);
        if (!String.valueOf(c.get("user_id")).equals(String.valueOf(usuarioId))) {
            return false;
        }
        revogar(refresh, c);
        return true;
    }

    @Transactional
    public void revogarTodos(Long usuarioId) {
        revogados.revogarTodosDoUsuario(usuarioId);
    }

    public Long validarAcesso(String access) {
        Map<String, Object> c = jwt.verificar(access);
        if (!"access".equals(c.get("token_type"))) throw new JwtInvalido("tipo");
        jtiDe(c);
        return idDoUsuario(c);
    }

    private ParDeTokens novoPar(String userId) {
        Instant agora = Instant.now();
        Map<String, Object> r = claims("refresh", userId, agora, REFRESH, UUID.randomUUID().toString().replace("-", ""));
        String refresh = jwt.assinar(r);
        emitidos.save(new TokenEmitido((String) r.get("jti"), refresh, odt(agora.getEpochSecond()),
                odt(((Number) r.get("exp")).longValue()), Long.valueOf(userId)));
        Map<String, Object> a = claims("access", userId, agora, ACCESS, UUID.randomUUID().toString().replace("-", ""));
        return new ParDeTokens(jwt.assinar(a), refresh);
    }

    private static Map<String, Object> claims(String tipo, String userId, Instant agora, Duration vida, String jti) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token_type", tipo);
        m.put("exp", agora.plus(vida).getEpochSecond());
        m.put("iat", agora.getEpochSecond());
        m.put("jti", jti);
        m.put("user_id", userId);
        return m;
    }

    private Map<String, Object> verificarRefresh(String refresh) {
        Map<String, Object> c = jwt.verificar(refresh);
        if (!"refresh".equals(c.get("token_type"))) throw new JwtInvalido("tipo");
        if (!(c.get("iat") instanceof Number)) throw new JwtInvalido("iat");
        if (revogados.existePorJti(jtiDe(c))) throw new JwtInvalido("revogado");
        return c;
    }

    private Usuario usuarioAtivo(Map<String, Object> c) {
        return usuarios.findById(idDoUsuario(c)).filter(Usuario::isActive).orElseThrow(() -> new JwtInvalido("usuario"));
    }

    private static Long idDoUsuario(Map<String, Object> c) {
        try {
            Object v = c.get("user_id");
            if (v instanceof Number n) return n.longValue();
            return Long.valueOf((String) v);
        } catch (RuntimeException e) {
            throw new JwtInvalido("user_id");
        }
    }

    /** simplejwt exige jti ("Token has no id"). */
    private static String jtiDe(Map<String, Object> c) {
        if (c.get("jti") instanceof String s && !s.isEmpty()) return s;
        throw new JwtInvalido("jti");
    }

    /** Como BlacklistMixin.blacklist: cria o outstanding se faltar (get_or_create). */
    private void revogar(String refresh, Map<String, Object> c) {
        String jti = jtiDe(c);
        // ON CONFLICT = get_or_create do simplejwt sem abortar a transacao em corrida.
        emitidos.inserirSeFaltar(jti, refresh, odt(((Number) c.get("iat")).longValue()),
                odt(((Number) c.get("exp")).longValue()), idDoUsuario(c));
        revogados.revogar(emitidos.findByJti(jti).orElseThrow().getId());
    }

    private static OffsetDateTime odt(long epoch) {
        return Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC);
    }
}
