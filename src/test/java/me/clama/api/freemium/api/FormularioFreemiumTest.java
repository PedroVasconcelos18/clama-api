package me.clama.api.freemium.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.api.CamposDrf;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FormularioFreemiumTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VALIDO = """
            {"nome":"  Maria Silva ","email":" Maria@Exemplo.com ","telefone":" +5511999998888 ",
             "cpf_cnpj":"529.982.247-25","idade":35,"sexo":"feminino","pedido_oracao":"  Peço oração.  ",
             "consent_aceito":true,"turnstile_token":"XXXX.DUMMY.TOKEN.XXXX","device_hash":"fp-abc123"}""";

    private static Map<String, List<String>> erros(String json) {
        return erros(JSON.readTree(json));
    }

    private static Map<String, List<String>> erros(JsonNode corpo) {
        return org.junit.jupiter.api.Assertions.assertThrows(CamposDrf.Invalidos.class,
                () -> FormularioFreemium.validar(corpo)).erros();
    }

    /** Os 20 corpos invalidos gravados do Django: mesmas mensagens, na mesma ordem de chaves. */
    @Test
    void camposInvalidosDoContrato() throws Exception {
        JsonNode passos;
        try (InputStream in = getClass().getResourceAsStream("/contrato/freemium/freemium_submit_campos_invalidos.json")) {
            passos = JSON.readTree(in).get("passos");
        }
        assertThat(passos.size()).isEqualTo(20);
        for (int i = 0; i < passos.size(); i++) {
            JsonNode passo = passos.get(i);
            assertThat(passo.get("response").get("status").asInt()).isEqualTo(400);
            Map<String, List<String>> esperado = new java.util.LinkedHashMap<>();
            passo.get("response").get("body").properties().forEach(e -> {
                List<String> msgs = new ArrayList<>();
                e.getValue().forEach(m -> msgs.add(m.asString()));
                esperado.put(e.getKey(), msgs);
            });
            Map<String, List<String>> obtido = erros(passo.get("request").get("body"));
            assertThat(obtido).as("passo %d", i).isEqualTo(esperado);
            assertThat(new ArrayList<>(obtido.keySet())).as("ordem do passo %d", i)
                    .isEqualTo(new ArrayList<>(esperado.keySet()));
        }
    }

    @Test
    void normalizaOCorpoValido() {
        PedidoFreemium p = FormularioFreemium.validar(JSON.readTree(VALIDO));
        assertThat(p).isEqualTo(new PedidoFreemium("Maria Silva", "maria@exemplo.com", "+5511999998888",
                "52998224725", 35, "feminino", "Peço oração.", "XXXX.DUMMY.TOKEN.XXXX", "fp-abc123"));
    }

    @Test
    void emailGmailViraCanonico() {
        PedidoFreemium p = FormularioFreemium.validar(
                JSON.readTree(VALIDO.replace(" Maria@Exemplo.com ", "Ma.Ria+x@Gmail.com")));
        assertThat(p.email()).isEqualTo("maria@gmail.com");
    }

    @Test
    void opcionaisAusentesTemOsDefaults() {
        PedidoFreemium p = FormularioFreemium.validar(JSON.readTree("""
                {"nome":"Maria","email":"m@exemplo.com","telefone":"+5511999998888","cpf_cnpj":"52998224725",
                 "consent_aceito":true,"turnstile_token":"t"}"""));
        assertThat(p.idade()).isNull();
        assertThat(p.sexo()).isEmpty();
        assertThat(p.pedidoOracao()).isEmpty();
        assertThat(p.deviceHash()).isEmpty();
    }

    @Test
    void idadeNulaESexoVazioPassam() {
        PedidoFreemium p = FormularioFreemium.validar(
                JSON.readTree(VALIDO.replace("\"idade\":35", "\"idade\":null").replace("\"feminino\"", "\"\"")));
        assertThat(p.idade()).isNull();
        assertThat(p.sexo()).isEmpty();
    }

    @Test
    void corpoQueNaoEObjetoDaNonFieldErrors() {
        assertThat(erros("[]")).isEqualTo(Map.of("non_field_errors",
                List.of("Dado inválido. Esperava um dicionário, mas recebeu list.")));
    }

    @Test
    void mensagensMortasNaoSaoPortadas() {
        // o blank do DRF vem antes das mensagens custom de telefone e CPF
        assertThat(erros(VALIDO.replace(" +5511999998888 ", "  "))).isEqualTo(
                Map.of("telefone", List.of("Este campo pode não estar em branco.")));
        assertThat(erros(VALIDO.replace("529.982.247-25", ""))).isEqualTo(
                Map.of("cpf_cnpj", List.of("Este campo pode não estar em branco.")));
    }

    @Test
    void variosErrosSaoDevolvidosNaOrdemDosCampos() {
        assertThatThrownBy(() -> FormularioFreemium.validar(JSON.readTree(
                "{\"nome\":\"A\",\"email\":\"x\",\"consent_aceito\":false,\"turnstile_token\":\"t\"}")))
                .isInstanceOfSatisfying(CamposDrf.Invalidos.class, e -> assertThat(new ArrayList<>(e.erros().keySet()))
                        .containsExactly("nome", "email", "telefone", "cpf_cnpj", "consent_aceito"));
    }
}
