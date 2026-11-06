package me.clama.api.identidade.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import me.clama.api.identidade.Usuario;

/** Payloads dos serializers do Django (clama_backend/users/api/serializers.py). */
public final class UsuarioDtos {
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo"); // TIME_ZONE do Django
    private static final DateTimeFormatter SEGUNDOS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private UsuarioDtos() {}

    /** AdminUserSerializer. */
    public record AdminUser(Long id, String email, String nome_completo) {
        static AdminUser de(Usuario u) {
            return new AdminUser(u.getId(), u.getEmail(), u.getNomeCompleto());
        }
    }

    /** CustomerUserSerializer: cpf_cnpj e telefone nulos viram "". */
    public record CustomerUser(Long id, String email, String nome_completo, boolean force_change_password,
            String freemium_used_at, String nome_format_blog, String cpf_cnpj, String telefone, Integer idade,
            String sexo) {
        static CustomerUser de(Usuario u) {
            return new CustomerUser(u.getId(), u.getEmail(), u.getNomeCompleto(), u.isForceChangePassword(),
                    dataHora(u.getFreemiumUsedAt()), u.getNomeFormatBlog(), vazioSeNulo(u.getCpfCnpj()),
                    vazioSeNulo(u.getTelefone()), u.getIdade(), u.getSexo());
        }
    }

    public record Login<T>(T user) {}

    /** DateTimeField do DRF: fuso local + isoformat() do Python (micro so se houver, "+00:00" vira "Z"). */
    public static String dataHora(OffsetDateTime t) {
        if (t == null) {
            return null;
        }
        OffsetDateTime local = t.atZoneSameInstant(FUSO).toOffsetDateTime();
        int micro = local.getNano() / 1000;
        return local.format(SEGUNDOS) + (micro == 0 ? "" : String.format(".%06d", micro)) + local.getOffset().getId();
    }

    private static String vazioSeNulo(String s) {
        return s == null ? "" : s;
    }
}
