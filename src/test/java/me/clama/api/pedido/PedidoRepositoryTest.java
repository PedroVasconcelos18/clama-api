package me.clama.api.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.cripto.CriptoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BancoDeTeste.class, CriptoConfig.class})
@ActiveProfiles("test")
class PedidoRepositoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired PedidoRepository repo;
    @Autowired PlanoRepository planos;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpa() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("DELETE FROM orders_pedido");
        jdbc.update("DELETE FROM plans_plan");
        jdbc.update("DELETE FROM instituicoes_instituicao");
    }

    private void inserir(String tabela, JsonNode linhas) {
        for (JsonNode linha : linhas) {
            // o gravador nao guarda os timestamps de plano e instituicao
            JsonNode l = linha.has("created_at") ? linha
                    : ((ObjectNode) linha.deepCopy()).put("created_at", "2026-11-01T13:00:00+00:00")
                            .put("updated_at", "2026-11-01T13:00:00+00:00");
            List<String> cols = new ArrayList<>();
            l.propertyNames().forEach(cols::add);
            String sql = "INSERT INTO " + tabela + " (" + String.join(",", cols) + ") VALUES ("
                    + String.join(",", cols.stream().map(c -> "?").toList()) + ")";
            jdbc.update(sql, ps -> {
                for (int i = 0; i < cols.size(); i++) {
                    JsonNode v = l.get(cols.get(i));
                    if (v.isNull()) ps.setNull(i + 1, Types.NULL);
                    else if (v.isBoolean()) ps.setBoolean(i + 1, v.asBoolean());
                    else if (v.isNumber()) ps.setInt(i + 1, v.asInt());
                    else ps.setObject(i + 1, v.asString(), Types.OTHER);
                }
            });
        }
    }

    /**
     * Pedido gravado pelo Python (gravador da Task 1: contrato/pedidos/checkout_cria_pix.json, dados). Decifrado com
     * o Python: .venv/bin/python (DJANGO_SETTINGS_MODULE=config.settings.test, FIELD_ENCRYPTION_KEY de teste)
     * decrypt_str(...) -> 'Maria Silva', 'maria@exemplo.com', '(11) 99999-8888', 'Peço oração pela minha família.',
     * '' (oracao_gerada) e '52998224725'.
     */
    @Test
    void lePedidoGravadoPeloPython() throws Exception {
        JsonNode dados = JSON.readTree(Path.of("src/test/resources/contrato/pedidos/checkout_cria_pix.json").toFile())
                .get("dados");
        inserir("plans_plan", dados.get("plans_plan"));
        inserir("instituicoes_instituicao", dados.get("instituicoes_instituicao"));
        inserir("orders_pedido", dados.get("orders_pedido"));

        Pedido p = repo.findById(UUID.fromString("77777777-7777-4777-8777-000000000001")).orElseThrow();
        assertThat(p.getNome()).isEqualTo("Maria Silva");
        assertThat(p.getEmail()).isEqualTo("maria@exemplo.com");
        assertThat(p.getTelefone()).isEqualTo("(11) 99999-8888");
        assertThat(p.getPedidoOracao()).isEqualTo("Peço oração pela minha família.");
        assertThat(p.getOracaoGerada()).isEmpty();
        assertThat(p.getCpfCnpj()).isEqualTo("52998224725");
        assertThat(p.getIdade()).isEqualTo(35);
        assertThat(p.getStatus()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO);
        assertThat(p.getCanalEntrega()).isEqualTo(CanalEntrega.EMAIL);
        assertThat(p.getConsentIp()).isEqualTo("203.0.113.7");
        assertThat(p.getPlano().getValorCentavos()).isEqualTo(2000);
        assertThat(p.getValorCentavos()).isEqualTo(3000);
        assertThat(p.valorReaisStr()).isEqualTo("R$ 30,00");
    }

    private Pedido novo() {
        Plano plano = new Plano();
        plano.setNome("P");
        plano.setValorCentavos(2000);
        plano.setDescricao("");
        planos.saveAndFlush(plano);
        Pedido p = new Pedido();
        p.setNome("João");
        p.setEmail("joao@x.com");
        p.setPlano(plano);
        p.setValorCentavos(2000);
        return p;
    }

    /** Os defaults gravados sao os do Django (estado_final da Task 1): vazios cifrados como token, nulls como null. */
    @Test
    void gravaComOsDefaultsDoDjango() {
        Pedido p = repo.saveAndFlush(novo());
        Map<String, Object> c = jdbc.queryForMap("SELECT * FROM orders_pedido WHERE id=?", p.getId());
        assertThat(c.get("telefone").toString()).startsWith("gAAAAA");
        assertThat(c.get("cpf_cnpj").toString()).startsWith("gAAAAA");
        assertThat(c.get("pedido_oracao").toString()).startsWith("gAAAAA");
        assertThat(c.get("oracao_gerada").toString()).startsWith("gAAAAA");
        assertThat(c).containsEntry("status", "aguardando_pagamento").containsEntry("canal_entrega", "email")
                .containsEntry("eh_gratuito", false).containsEntry("sexo", "").containsEntry("last_error", "")
                .containsEntry("whatsapp_message_id", "").containsEntry("device_hash", "")
                .containsEntry("consent_versao", "").containsEntry("consent_aceito", false);
        assertThat(((Number) c.get("retry_count")).intValue()).isZero();
        assertThat(c.get("oracao_email_sent_at")).isNull();
        assertThat(c.get("whatsapp_delivered_at")).isNull();
        assertThat(c.get("whatsapp_read_at")).isNull();
        assertThat(c.get("consent_ip")).isNull();
        assertThat(c.get("idade")).isNull();
        assertThat(c.get("provider_payment_id")).isNull();
        assertThat(c.get("instituicao_id")).isNull();
        assertThat(c.get("user_id")).isNull();
        Pedido lido = repo.findById(p.getId()).orElseThrow();
        assertThat(lido.getTelefone()).isEmpty();
        assertThat(lido.getNome()).isEqualTo("João");
    }

    @Test
    void consentIpInetEUpdatedAtAutoNow() throws Exception {
        Pedido p = novo();
        p.setConsentIp("2001:db8::1");
        repo.saveAndFlush(p);
        assertThat(jdbc.queryForObject("SELECT host(consent_ip) FROM orders_pedido WHERE id=?", String.class, p.getId()))
                .isEqualTo("2001:db8::1");
        var antes = p.getUpdatedAt();
        Thread.sleep(5);
        p.setStatus(StatusPedido.ENVIADA);
        repo.saveAndFlush(p);
        assertThat(p.getUpdatedAt()).isAfter(antes);
        assertThat(repo.findById(p.getId()).orElseThrow().getConsentIp()).isEqualTo("2001:db8::1");
    }

    /** Linha gravada pelo Java para o Python decifrar: ver target/pedido-java.json e o comando no relatorio. */
    @Test
    void gravaPedidoQueOPythonLe() throws Exception {
        Pedido p = novo();
        p.setTelefone("(11) 99999-8888");
        p.setCpfCnpj("52998224725");
        p.setPedidoOracao("Peço oração pela minha família.");
        p.setConsentIp("203.0.113.9");
        repo.saveAndFlush(p);
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT nome,email,telefone,cpf_cnpj,pedido_oracao,oracao_gerada FROM orders_pedido WHERE id=?", p.getId());
        Path out = Path.of("target/pedido-java.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writeValueAsString(c));
    }

    @Test
    void marcarComoPagoSoDeAguardandoPagamento() {
        Pedido p = novo();
        p.marcarComoPago();
        assertThat(p.getStatus()).isEqualTo(StatusPedido.PAGO);
        assertThatThrownBy(p::marcarComoPago).isInstanceOf(TransicaoInvalida.class);
        assertThat(p.getStatus()).isEqualTo(StatusPedido.PAGO);
    }

    @Test
    void atualizarSoOStatusNaoReescreveAsColunasCifradas() {
        Pedido p = novo();
        p.setTelefone("(11) 99999-8888");
        p.setCpfCnpj("52998224725");
        p.setPedidoOracao("Peço oração.");
        Pedido salvo = repo.saveAndFlush(p);
        String sql = "SELECT nome,email,telefone,cpf_cnpj,pedido_oracao,oracao_gerada FROM orders_pedido WHERE id=?";
        Map<String, Object> antes = jdbc.queryForMap(sql, salvo.getId());

        salvo.setStatus(StatusPedido.ENVIADA);
        repo.saveAndFlush(salvo);

        // Fernet tem IV aleatorio: reescrever a coluna mudaria o token
        assertThat(jdbc.queryForMap(sql, salvo.getId())).isEqualTo(antes);
        assertThat(jdbc.queryForObject("SELECT status FROM orders_pedido WHERE id=?", String.class, salvo.getId()))
                .isEqualTo("enviada");
    }
}
