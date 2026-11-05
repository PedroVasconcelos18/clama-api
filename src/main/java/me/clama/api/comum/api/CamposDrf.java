package me.clama.api.comum.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.clama.api.comum.texto.TextoPython;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Validacao de corpo do DRF (Serializer + CharField), com as mensagens pt-BR do DRF/Django. */
public final class CamposDrf {
    public static final String OBRIGATORIO = "Este campo é obrigatório.";
    public static final String NULO = "Este campo pode não ser nulo.";
    static final String BRANCO = "Este campo pode não estar em branco.";
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
            String erro = corpo.isNull() ? NULO
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
        JsonNode v = corpo.get(campo);
        if (v == null) {
            return erro(campo, OBRIGATORIO);
        }
        if (v.isNull()) {
            return erro(campo, NULO);
        }
        if (!v.isString() && !v.isNumber()) { // bool tambem e int no Python, mas o DRF recusa
            return erro(campo, NAO_E_STRING);
        }
        String s = TextoPython.strip(v.asString());
        if (s.isEmpty()) {
            return erro(campo, BRANCO);
        }
        List<String> msgs = new ArrayList<>(validadores.apply(s));
        if (s.indexOf('\0') >= 0) {
            msgs.add(CARACTERES_NULOS);
        }
        if (!msgs.isEmpty()) {
            erros.put(campo, msgs);
            return null;
        }
        return s;
    }

    public String texto(String campo) {
        return texto(campo, s -> List.of());
    }

    public String erro(String campo, String msg) {
        erros.computeIfAbsent(campo, k -> new ArrayList<>()).add(msg);
        return null;
    }

    /** is_valid(raise_exception=True). */
    public void validar() {
        if (!erros.isEmpty()) {
            throw new Invalidos(erros);
        }
    }

    private static String tipoPython(JsonNode n) {
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
