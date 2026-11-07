package me.clama.api.freemium.api;

import java.util.List;
import java.util.regex.Pattern;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.email.ValidadorDeEmail;
import tools.jackson.databind.JsonNode;

/**
 * PedidoFreemiumCreateRequestSerializer (clama/freemium/api/serializers.py): campos na ordem do serializer (a dos
 * erros). As mensagens custom de vazio de telefone e CPF sao mortas no Django (o blank do DRF vem antes) e nao sao
 * portadas.
 */
public final class FormularioFreemium {
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private FormularioFreemium() {}

    /** is_valid(raise_exception=True) + a normalizacao local do pipeline (views.py:224-233). */
    public static PedidoFreemium validar(JsonNode corpo) {
        CamposDrf c = new CamposDrf(corpo);
        String nome = c.texto("nome", true, CamposDrf.BRANCO,
                s -> concat(CamposDrf.maxLength(120).apply(s), CamposDrf.minLength(2).apply(s)));
        String email = c.texto("email", true, CamposDrf.BRANCO, s -> List.of(),
                s -> ValidadorDeEmail.valido(s) ? List.of() : List.of("Insira um endereço de email válido."));
        // validate_telefone so roda se o max_length passou (erro de campo pula validate_<campo>)
        String telefone = c.texto("telefone", true, CamposDrf.BRANCO, s -> {
            List<String> m = CamposDrf.maxLength(20).apply(s);
            return !m.isEmpty() || E164.matcher(s).matches() ? m
                    : List.of("Telefone deve estar no formato internacional (ex.: +5511999999999).");
        });
        String cpf = c.documento("cpf_cnpj", c.texto("cpf_cnpj", true, CamposDrf.BRANCO, CamposDrf.maxLength(20)));
        JsonNode idade = c.get("idade");
        Integer anos = idade != null && idade.isNull() ? null : c.inteiro("idade", false, 1, 120);
        String sexo = c.escolha("sexo", false, List.of("", "feminino", "masculino", "nao_informado"));
        String oracao = c.texto("pedido_oracao", false, null, CamposDrf.maxLength(2000));
        if (Boolean.FALSE.equals(c.booleano("consent_aceito", true))) {
            c.erro("consent_aceito", "Para enviar seu pedido, é preciso concordar com a política de privacidade.");
        }
        String turnstile = c.texto("turnstile_token", true, CamposDrf.BRANCO, CamposDrf.maxLength(2048));
        String deviceHash = c.texto("device_hash", false, null, CamposDrf.maxLength(128));
        c.validar();
        return new PedidoFreemium(nome, HashIdentificador.normalizarEmail(email), telefone, cpf, anos,
                sexo == null ? "" : sexo, oracao == null ? "" : oracao, turnstile, deviceHash == null ? "" : deviceHash);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
