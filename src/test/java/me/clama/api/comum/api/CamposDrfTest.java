package me.clama.api.comum.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Textos esperados calculados no DRF real (uma vez):
 * cd clama-backend && DJANGO_SETTINGS_MODULE=config.settings.test \
 *   FIELD_ENCRYPTION_KEY=-dPyxte5HDDyxBQExFzkphbnXt2xhs9FWlMLPFQpeuw= .venv/bin/python -c \
 *   'import django;django.setup();from rest_framework import serializers as s; ...run_validation(valor)'
 * com BooleanField(required=False), ChoiceField(choices=["a","b"]), CharField(max_length=80) e AdminPlanSerializer.
 */
class CamposDrfTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static CamposDrf c(String json) {
        return new CamposDrf(JSON.readTree(json));
    }

    static Map<String, List<String>> erros(CamposDrf c) {
        try {
            c.validar();
            return Map.of();
        } catch (CamposDrf.Invalidos e) {
            return e.erros();
        }
    }

    @Test
    void maxLengthEmPortugues() {
        CamposDrf c = c("{\"nome\":\"" + "x".repeat(81) + "\"}");
        c.texto("nome", true, CamposDrf.maxLength(80));
        assertThat(erros(c)).containsEntry("nome",
                List.of("Certifique-se de que este campo não tenha mais de 80 caracteres."));
        CamposDrf ok = c("{\"nome\":\"" + "x".repeat(80) + "\"}");
        assertThat(ok.texto("nome", true, CamposDrf.maxLength(80))).hasSize(80);
        assertThat(erros(ok)).isEmpty();
    }

    @Test
    void booleanoAceitaOsValoresDoDrf() {
        CamposDrf c = c("{\"a\":1.0,\"b\":0.0,\"c\":true,\"d\":false,\"e\":\"1\",\"f\":\"off\",\"g\":\"Yes\",\"h\":1,\"i\":0}");
        assertThat(List.of("a", "c", "e", "g", "h")).allSatisfy(k -> assertThat(c.booleano(k, false)).isTrue());
        assertThat(List.of("b", "d", "f", "i")).allSatisfy(k -> assertThat(c.booleano(k, false)).isFalse());
        assertThat(erros(c)).isEmpty();
    }

    @Test
    void booleanoInvalidoNuloEAusente() {
        CamposDrf c = c("{\"x\":\"x\",\"y\":2,\"z\":1.5,\"w\":[],\"n\":null}");
        for (String k : List.of("x", "y", "z", "w")) {
            c.booleano(k, false);
        }
        c.booleano("n", false);
        c.booleano("ausente", false);
        c.booleano("exigido", true);
        Map<String, List<String>> e = erros(c);
        for (String k : List.of("x", "y", "z", "w")) {
            assertThat(e.get(k)).containsExactly("Deve ser um valor booleano válido.");
        }
        assertThat(e.get("n")).containsExactly("Este campo pode não ser nulo.");
        assertThat(e).doesNotContainKey("ausente");
        assertThat(e.get("exigido")).containsExactly("Este campo é obrigatório.");
    }

    @Test
    void escolhaRenderizaComoStrDoPython() {
        CamposDrf c = c("{\"t\":true,\"f\":false,\"n5\":5,\"x\":\"x\",\"d\":1.5,\"v\":\"\",\"nulo\":null,\"ok\":\"a\"}");
        for (String k : List.of("t", "f", "n5", "x", "d", "v", "nulo")) {
            c.escolha(k, false, List.of("a", "b"));
        }
        assertThat(c.escolha("ok", false, List.of("a", "b"))).isEqualTo("a");
        assertThat(c.escolha("ausente", false, List.of("a", "b"))).isNull();
        c.escolha("exigido", true, List.of("a", "b"));
        Map<String, List<String>> e = erros(c);
        assertThat(e.get("t")).containsExactly("\"True\" não é uma escolha válida.");
        assertThat(e.get("f")).containsExactly("\"False\" não é uma escolha válida.");
        assertThat(e.get("n5")).containsExactly("\"5\" não é uma escolha válida.");
        assertThat(e.get("x")).containsExactly("\"x\" não é uma escolha válida.");
        assertThat(e.get("d")).containsExactly("\"1.5\" não é uma escolha válida.");
        assertThat(e.get("v")).containsExactly("\"\" não é uma escolha válida.");
        assertThat(e.get("nulo")).containsExactly("Este campo pode não ser nulo.");
        assertThat(e).doesNotContainKey("ausente");
        assertThat(e.get("exigido")).containsExactly("Este campo é obrigatório.");
    }

    @Test
    void obrigatorioExplicitoEmTextoEInteiro() {
        CamposDrf c = c("{}");
        assertThat(c.texto("a", false, s -> List.of())).isNull();
        assertThat(c.inteiro("b", false, 0, 10)).isNull();
        assertThat(erros(c)).isEmpty();
        c.texto("a", true, s -> List.of());
        c.inteiro("b", true, 0, 10);
        assertThat(erros(c)).containsOnlyKeys("a", "b");
    }
}
