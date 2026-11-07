package me.clama.api.comum.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import me.clama.api.comum.documento.ValidadorDocumento;
import me.clama.api.comum.texto.TextoPython;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Validacao de corpo do DRF (Serializer + CharField), com as mensagens pt-BR do DRF/Django. */
public final class CamposDrf {
    public static final String OBRIGATORIO = "Este campo é obrigatório.";
    public static final String NULO = "Este campo pode não ser nulo.";
    public static final String BRANCO = "Este campo pode não estar em branco.";
    static final String NAO_E_STRING = "Não é uma string válida.";
    static final String CARACTERES_NULOS = "Caracteres nulos não são permitidos.";

    /** 400 {"campo":["msg", ...]}; vira resposta no PastoralExceptionHandler. */
    public static class Invalidos extends RuntimeException {
        private final Map<String, List<String>> erros;

        public Invalidos(Map<String, List<String>> erros) {
            super(null, null, false, false);
            this.erros = erros;
        }

        public Map<String, List<String>> erros() {
            return erros;
        }
    }

    private final JsonNode corpo;
    private final Map<String, List<String>> erros = new LinkedHashMap<>();

    /** request.data: sem corpo vale {}; corpo que nao e objeto da non_field_errors. */
    public CamposDrf(JsonNode corpo) {
        if (corpo == null) {
            this.corpo = JsonNodeFactory.instance.objectNode();
            return;
        }
        if (!corpo.isObject()) {
            // Serializer.errors troca o "null" do corpo nulo por "No data provided" (sem traducao no DRF)
            String erro = corpo.isNull() ? "No data provided"
                    : "Dado inválido. Esperava um dicionário, mas recebeu " + tipoPython(corpo) + ".";
            throw new Invalidos(Map.of("non_field_errors", List.of(erro)));
        }
        this.corpo = corpo;
    }

    public JsonNode get(String campo) {
        return corpo.get(campo);
    }

    /** CharField(trim_whitespace=True, allow_blank=False). validadores rodam no valor ja aparado. */
    public String texto(String campo, Function<String, List<String>> validadores) {
        return texto(campo, true, validadores);
    }

    /** obrigatorio=false (PATCH, ou campo com default): ausente devolve null sem erro. */
    public String texto(String campo, boolean obrigatorio, Function<String, List<String>> validadores) {
        return texto(campo, obrigatorio, BRANCO, validadores);
    }

    /**
     * msgBranco: error_messages["blank"] do campo; null = allow_blank (devolve "" sem rodar os validadores, como o
     * DRF). Os validadores recebem o valor ja aparado.
     */
    public String texto(String campo, boolean obrigatorio, String msgBranco,
            Function<String, List<String>> validadores) {
        return texto(campo, obrigatorio, msgBranco, validadores, s -> List.of());
    }

    /**
     * Ordem dos validadores do CharField do DRF: max/min_length (validadores), caracteres nulos, surrogates e por
     * ultimo os do tipo do campo (doTipo, ex. o EmailValidator do EmailField).
     */
    public String texto(String campo, boolean obrigatorio, String msgBranco,
            Function<String, List<String>> validadores, Function<String, List<String>> doTipo) {
        JsonNode v = corpo.get(campo);
        if (v == null) {
            return obrigatorio ? erro(campo, OBRIGATORIO) : null;
        }
        if (v.isNull()) {
            return erro(campo, NULO);
        }
        if (!v.isString() && !v.isNumber()) { // bool tambem e int no Python, mas o DRF recusa
            return erro(campo, NAO_E_STRING);
        }
        String s = TextoPython.strip(v.asString());
        if (s.isEmpty()) {
            return msgBranco == null ? s : erro(campo, msgBranco);
        }
        List<String> msgs = new ArrayList<>(validadores.apply(s));
        if (s.indexOf('\0') >= 0) {
            msgs.add(CARACTERES_NULOS);
        }
        // ProhibitSurrogateCharactersValidator: so o primeiro surrogate solto (par valido vira um code point)
        s.codePoints().filter(c -> c >= 0xD800 && c <= 0xDFFF).findFirst().ifPresent(c -> msgs.add("Caracteres "
                + "substitutos não são permitidos: U+" + Integer.toHexString(c).toUpperCase(Locale.ROOT) + "."));
        msgs.addAll(doTipo.apply(s));
        if (!msgs.isEmpty()) {
            erros.put(campo, msgs);
            return null;
        }
        return s;
    }

    public String texto(String campo) {
        return texto(campo, s -> List.of());
    }

    /** CharField(allow_blank=True, required=False) do TextField(blank=True): ausente devolve null. */
    public String textoEmBranco(String campo) {
        return texto(campo, false, null, s -> List.of());
    }

    public String erro(String campo, String msg) {
        erros.computeIfAbsent(campo, k -> new ArrayList<>()).add(msg);
        return null;
    }

    /** IntegerField(min_value, max_value): aceita int, float inteiro ("12.0") e string numerica; bool nao. */
    public Integer inteiro(String campo, boolean obrigatorio, int min, int max) {
        return inteiro(campo, obrigatorio, min, max, "Certifque-se de que este valor seja maior ou igual a " + min + ".");
    }

    /** msgMin: error_messages["min_value"] do campo (o texto padrao do DRF pt-BR tem o erro "Certifque"). */
    public Integer inteiro(String campo, boolean obrigatorio, int min, int max, String msgMin) {
        Long n = longo(campo, obrigatorio, min, max, msgMin);
        return n == null ? null : n.intValue();
    }

    /** O mesmo IntegerField, com limites em long (ids bigint). */
    public Long longo(String campo, boolean obrigatorio, long min, long max, String msgMin) {
        JsonNode v = corpo.get(campo);
        if (v == null) {
            if (obrigatorio) {
                erro(campo, OBRIGATORIO);
            }
            return null;
        }
        if (v.isNull()) {
            erro(campo, NULO);
            return null;
        }
        java.math.BigInteger n = null;
        if (v.isIntegralNumber()) {
            n = v.bigIntegerValue();
        } else if (v.isNumber() && v.decimalValue().stripTrailingZeros().scale() <= 0) {
            n = v.decimalValue().toBigIntegerExact();
        } else if (v.isString() && v.asString().strip().matches("[+-]?\\d+(\\.0*)?")) {
            n = new java.math.BigDecimal(v.asString().strip()).toBigInteger();
        }
        if (n == null) {
            erro(campo, "Um número inteiro válido é exigido.");
            return null;
        }
        if (n.compareTo(java.math.BigInteger.valueOf(min)) < 0) {
            erro(campo, msgMin);
            return null;
        }
        if (n.compareTo(java.math.BigInteger.valueOf(max)) > 0) {
            erro(campo, "Certifique-se de que este valor seja inferior ou igual a " + max + ".");
            return null;
        }
        return n.longValue();
    }

    private static final java.util.Set<String> VERDADEIROS = java.util.Set.of("t", "T", "y", "Y", "yes", "Yes", "YES",
            "true", "True", "TRUE", "on", "On", "ON", "1");
    private static final java.util.Set<String> FALSOS = java.util.Set.of("f", "F", "n", "N", "no", "No", "NO", "false",
            "False", "FALSE", "off", "Off", "OFF", "0");

    /** BooleanField: TRUE_VALUES/FALSE_VALUES do DRF, inclusive numeros 1.0 e 0.0 (1.0 == 1 no Python). */
    public Boolean booleano(String campo, boolean obrigatorio) {
        JsonNode v = corpo.get(campo);
        if (v == null) {
            if (obrigatorio) {
                erro(campo, OBRIGATORIO);
            }
            return null;
        }
        if (v.isNull()) {
            erro(campo, NULO);
            return null;
        }
        if (v.isNumber()) {
            java.math.BigDecimal d = v.decimalValue();
            if (d.compareTo(java.math.BigDecimal.ONE) == 0) {
                return true;
            }
            if (d.signum() == 0) {
                return false;
            }
        }
        String s = v.isContainer() ? "" : v.asString();
        if (VERDADEIROS.contains(s)) {
            return true;
        }
        if (FALSOS.contains(s)) {
            return false;
        }
        erro(campo, "Deve ser um valor booleano válido.");
        return null;
    }

    /** ChoiceField: devolve o valor se for uma das opcoes. */
    public String escolha(String campo, boolean obrigatorio, List<String> opcoes) {
        return escolha(campo, obrigatorio, opcoes, null);
    }

    /** msgInvalida: error_messages["invalid_choice"] do campo (null = o padrao, com o valor). */
    public String escolha(String campo, boolean obrigatorio, List<String> opcoes, String msgInvalida) {
        JsonNode v = corpo.get(campo);
        if (v == null) {
            return obrigatorio ? erro(campo, OBRIGATORIO) : null;
        }
        if (v.isNull()) {
            return erro(campo, NULO);
        }
        String s = TextoPython.str(v);
        if (v.isString() && opcoes.contains(s)) {
            return s;
        }
        return erro(campo, msgInvalida != null ? msgInvalida : "\"" + s + "\" não é uma escolha válida.");
    }

    /** MaxLengthValidator do CharField (conta code points, como o len() do Python). */
    public static Function<String, List<String>> maxLength(int n) {
        return s -> s.codePointCount(0, s.length()) > n
                ? List.of("Certifique-se de que este campo não tenha mais de " + n + " caracteres.") : List.of();
    }

    /** MinLengthValidator do CharField, com o texto padrao do DRF pt-BR (que diz "mais de", nao "pelo menos"). */
    public static Function<String, List<String>> minLength(int n) {
        return s -> s.codePointCount(0, s.length()) < n
                ? List.of("Certifique-se de que este campo tenha mais de " + n + " caracteres.") : List.of();
    }

    /** validate_cpf_cnpj: devolve so os digitos, ou registra o erro do campo e devolve null. valor nulo passa direto. */
    public String documento(String campo, String valor) {
        if (valor == null) {
            return null;
        }
        String d = ValidadorDocumento.soDigitos(valor);
        int n = d.codePointCount(0, d.length());
        if (n == 11) {
            return ValidadorDocumento.cpfValido(d) ? d
                    : erro(campo, "Confira seu CPF — parece que tem algum dígito errado.");
        }
        if (n == 14) {
            return ValidadorDocumento.cnpjValido(d) ? d
                    : erro(campo, "Confira seu CNPJ — parece que tem algum dígito errado.");
        }
        return erro(campo, "CPF deve ter 11 dígitos ou CNPJ deve ter 14 dígitos.");
    }

    /** is_valid(raise_exception=True). */
    public void validar() {
        if (!erros.isEmpty()) {
            throw new Invalidos(erros);
        }
    }

    public static String tipoPython(JsonNode n) {
        if (n.isArray()) {
            return "list";
        }
        if (n.isString()) {
            return "str";
        }
        if (n.isBoolean()) {
            return "bool";
        }
        return n.isIntegralNumber() ? "int" : "float";
    }
}
