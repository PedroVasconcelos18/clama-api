package me.clama.api.identidade.api;

import static me.clama.api.comum.api.DatasDrf.dataHora;

import me.clama.api.identidade.Usuario;

/** Payloads dos serializers do Django (clama_backend/users/api/serializers.py). */
public final class UsuarioDtos {
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

    private static String vazioSeNulo(String s) {
        return s == null ? "" : s;
    }
}
