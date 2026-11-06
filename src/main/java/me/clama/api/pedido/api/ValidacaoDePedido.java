package me.clama.api.pedido.api;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import me.clama.api.catalogo.instituicao.Instituicao;
import me.clama.api.catalogo.instituicao.InstituicaoRepository;
import me.clama.api.catalogo.plano.InferenciaDePlano;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.documento.ValidadorDocumento;
import me.clama.api.comum.email.ValidadorDeEmail;
import me.clama.api.comum.ratelimit.ClienteIp;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.pedido.CanalEntrega;
import me.clama.api.pedido.Pedido;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * PedidoCreateSerializer e DoacaoAnonimaCreateSerializer (clama/orders/api/serializers.py): campos na ordem do
 * serializer (a dos erros), depois o validate() (WhatsApp sem telefone, plano inferido do valor).
 */
@Component
class ValidacaoDePedido {
    static final String TURNSTILE = "Verificação anti-robô falhou. Atualize a página e tente de novo.";
    private static final String POLITICA_VERSAO_ATUAL = "1"; // clama/core/legal.py

    private final PlanoRepository planos;
    private final InstituicaoRepository instituicoes;
    private final InferenciaDePlano inferencia;

    ValidacaoDePedido(PlanoRepository planos, InstituicaoRepository instituicoes, InferenciaDePlano inferencia) {
        this.planos = planos;
        this.instituicoes = instituicoes;
        this.inferencia = inferencia;
    }

    /** validated_data; turnstileToken so na doacao. */
    record Dados(Pedido pedido, String turnstileToken) {}

    /** doacao: sem o campo plano (sempre inferido), com device_hash e turnstile_token. */
    Dados validar(JsonNode corpo, boolean doacao) {
        return validar(corpo, doacao, false);
    }

    /** PedidoGratuitoCreateSerializer: sem plano, instituicao e valor_centavos (o controller forca o plano gratuito). */
    Pedido validarGratuito(JsonNode corpo) {
        return validar(corpo, false, true).pedido();
    }

    private Dados validar(JsonNode corpo, boolean doacao, boolean gratuito) {
        CamposDrf c = new CamposDrf(corpo);
        Pedido p = new Pedido();
        String nome = c.texto("nome", true, "Por favor, digite seu nome.", s -> {
            List<String> m = new ArrayList<>(CamposDrf.maxLength(120).apply(s));
            if (s.codePointCount(0, s.length()) < 2) {
                m.add("Conta um pouco de você no nome — pelo menos 2 letras.");
            }
            return m;
        });
        String email = c.texto("email", true, "Por favor, digite seu e-mail.", CamposDrf.maxLength(254),
                s -> ValidadorDeEmail.valido(s) ? List.of() : List.of("Confira seu e-mail — parece que faltou algo."));
        String telefone = c.texto("telefone", false, null, CamposDrf.maxLength(20));
        String cpf = documento(c, c.texto("cpf_cnpj", true, null, CamposDrf.maxLength(18)));
        JsonNode idade = c.get("idade");
        Integer anos = idade != null && idade.isNull() ? null : c.inteiro("idade", false, 0, Short.MAX_VALUE);
        String sexo = c.escolha("sexo", false, List.of("", "feminino", "masculino", "nao_informado"));
        String oracao = c.textoEmBranco("pedido_oracao");
        Plano plano = doacao || gratuito ? null : plano(c);
        Instituicao instituicao = gratuito ? null : relacionado(c, "instituicao", instituicoes::findByIdAndAtivoTrue,
                "Não encontramos essa instituição. Escolha uma da lista, por favor.",
                "Instituição inválida. Escolha uma da lista, por favor.");
        Integer valor = gratuito ? Integer.valueOf(0) : c.inteiro("valor_centavos", true, 100, Integer.MAX_VALUE,
                "O valor mínimo é R$ 1,00 — qualquer oferta acima ajuda o Clama.");
        String canal = c.escolha("canal_entrega", false, List.of("email", "whatsapp"),
                "Por favor, escolha entre E-mail ou WhatsApp.");
        if (Boolean.FALSE.equals(c.booleano("consent_aceito", true))) {
            c.erro("consent_aceito", "Para enviar seu pedido, é preciso concordar com a política de privacidade.");
        }
        String deviceHash = null;
        String token = null;
        if (doacao) {
            deviceHash = c.texto("device_hash", false, null, CamposDrf.maxLength(128));
            token = c.get("turnstile_token") == null ? c.erro("turnstile_token", TURNSTILE)
                    : c.texto("turnstile_token", true, TURNSTILE, CamposDrf.maxLength(2048));
        }
        c.validar();

        CanalEntrega canalEntrega = "whatsapp".equals(canal) ? CanalEntrega.WHATSAPP : CanalEntrega.EMAIL;
        if (canalEntrega == CanalEntrega.WHATSAPP && (telefone == null || TextoPython.strip(telefone).isEmpty())) {
            throw invalido("telefone", "Para receber no WhatsApp, precisamos do seu telefone.");
        }
        if (plano == null && !gratuito) {
            plano = inferencia.inferirPorValor(valor).or(inferencia::fallbackValorLivre)
                    .orElseThrow(() -> invalido("plano", "Nenhum plano disponível no momento."));
        }
        p.setNome(nome);
        p.setEmail(email);
        p.setTelefone(telefone == null ? "" : telefone);
        p.setCpfCnpj(cpf);
        p.setIdade(anos); // ja na faixa 0..32767 do smallint
        p.setSexo(sexo == null ? "" : sexo);
        p.setPedidoOracao(oracao == null ? "" : oracao);
        p.setPlano(plano);
        p.setInstituicao(instituicao);
        p.setValorCentavos(valor);
        p.setCanalEntrega(canalEntrega);
        p.setDeviceHash(deviceHash == null ? "" : deviceHash);
        return new Dados(p, token);
    }

    /** create(): consentimento LGPD (consent_ip nulo quando o XFF/REMOTE_ADDR nao e IP: o inet nao aceita). */
    static void consentir(Pedido p, HttpServletRequest req) {
        p.setConsentAceito(true);
        p.setConsentVersao(POLITICA_VERSAO_ATUAL);
        p.setConsentAceitoAt(OffsetDateTime.now());
        p.setConsentIp(ClienteIp.validado(req));
    }

    /** validate_cpf_cnpj: guarda so os digitos. */
    private static String documento(CamposDrf c, String valor) {
        if (valor == null) {
            return null;
        }
        String d = ValidadorDocumento.soDigitos(valor);
        int n = d.codePointCount(0, d.length());
        if (n == 11) {
            return ValidadorDocumento.cpfValido(d) ? d
                    : c.erro("cpf_cnpj", "Confira seu CPF — parece que tem algum dígito errado.");
        }
        if (n == 14) {
            return ValidadorDocumento.cnpjValido(d) ? d
                    : c.erro("cpf_cnpj", "Confira seu CNPJ — parece que tem algum dígito errado.");
        }
        return c.erro("cpf_cnpj", "CPF deve ter 11 dígitos ou CNPJ deve ter 14 dígitos.");
    }

    /** PrimaryKeyRelatedField(queryset=Plan.objects.all()) + validate_plano. */
    private Plano plano(CamposDrf c) {
        Plano p = relacionado(c, "plano", planos::findById, null, null);
        if (p != null && !p.isAtivo()) {
            c.erro("plano", "Esse plano não está disponível no momento.");
            return null;
        }
        if (p != null && !p.isVisivel()) {
            c.erro("plano", "Esse plano não está disponível.");
            return null;
        }
        return p;
    }

    /**
     * PrimaryKeyRelatedField(allow_null=True) com pk UUID: "" e null valem None; bool e incorrect_type; o resto passa
     * pelo UUIDField.to_python do Django (int vira UUID(int=...), texto UUID(hex=...)) antes da busca.
     */
    private static <T> T relacionado(CamposDrf c, String campo, Function<UUID, Optional<T>> buscar,
            String msgInexistente, String msgTipo) {
        JsonNode v = c.get(campo);
        if (v == null || v.isNull() || (v.isString() && v.asString().isEmpty())) {
            return null;
        }
        if (v.isBoolean()) {
            c.erro(campo, msgTipo != null ? msgTipo : "Tipo incorreto. Esperava valor pk, recebeu bool.");
            return null;
        }
        UUID id = v.isIntegralNumber() ? uuid(v.bigIntegerValue()) : v.isString() ? uuidHex(v.asString()) : null;
        if (id == null) {
            c.erro(campo, "O valor “" + TextoPython.str(v) + "” não é um UUID válido");
            return null;
        }
        Optional<T> achado = buscar.apply(id);
        if (achado.isEmpty()) {
            c.erro(campo, msgInexistente != null ? msgInexistente
                    : "Pk inválido \"" + TextoPython.str(v) + "\" - objeto não existe.");
        }
        return achado.orElse(null);
    }

    /** uuid.UUID(hex=...). ponytail: o int() do Python tambem aceita "_" e espacos nas pontas; aqui nao. */
    static UUID uuidHex(String s) {
        String h = s.replace("urn:", "").replace("uuid:", "");
        int i = 0;
        int f = h.length();
        while (i < f && (h.charAt(i) == '{' || h.charAt(i) == '}')) {
            i++;
        }
        while (f > i && (h.charAt(f - 1) == '{' || h.charAt(f - 1) == '}')) {
            f--;
        }
        h = h.substring(i, f).replace("-", "");
        if (h.codePointCount(0, h.length()) != 32) {
            return null;
        }
        try {
            return uuid(new BigInteger(h, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID uuid(BigInteger n) {
        if (n.signum() < 0 || n.bitLength() > 128) {
            return null;
        }
        return new UUID(n.shiftRight(64).longValue(), n.longValue());
    }

    private static CamposDrf.Invalidos invalido(String campo, String msg) {
        return new CamposDrf.Invalidos(Map.of(campo, List.of(msg)));
    }
}
