package me.clama.api.comum.cripto;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Carrega o fixture gravado pelo Python (compat-cripto.json). */
final class CompatCripto {
    private static final Map<String, Object> RAIZ = carregar();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> carregar() {
        try (InputStream in = CompatCripto.class.getResourceAsStream("/contrato/compat-cripto.json")) {
            return new ObjectMapper().readValue(in, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> secao(String nome) {
        return (Map<String, Object>) RAIZ.get(nome);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, String>> casos(String secao) {
        return (List<Map<String, String>>) secao(secao).get("casos");
    }
}
