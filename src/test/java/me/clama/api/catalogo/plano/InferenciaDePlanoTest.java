package me.clama.api.catalogo.plano;

import static org.assertj.core.api.Assertions.assertThat;

import me.clama.api.BancoDeTeste;
import me.clama.api.comum.cripto.CriptoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** PlanManager.infer_from_valor / fallback_valor_livre (clama/plans/models.py) sobre os tres tiers + Gratuito + Livre. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BancoDeTeste.class, CriptoConfig.class, InferenciaDePlano.class})
@ActiveProfiles("test")
class InferenciaDePlanoTest {
    @Autowired InferenciaDePlano inferencia;
    @Autowired JdbcTemplate jdbc;

    private void plano(String nome, int valor, String complexidade, int ordem, boolean ativo, boolean visivel) {
        jdbc.update("""
                INSERT INTO plans_plan (id,created_at,updated_at,nome,valor_centavos,descricao,complexidade,ordem,ativo,visivel)
                VALUES (gen_random_uuid(),now(),now(),?,?,'',?,?,?,?)""", nome, valor, complexidade, ordem, ativo, visivel);
    }

    @BeforeEach
    void seeds() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("DELETE FROM plans_plan");
        plano("Pedido de Oracao", 2000, "simples", 1, true, true);
        plano("Oracao + Versiculo", 5000, "com_versiculo", 2, true, true);
        plano("Oracao + Profecia", 10000, "com_profecia_e_versiculos", 3, true, true);
        plano("Antigo", 1500, "simples", 0, false, true);
        plano("Gratuito", 0, "simples_gratuita", 9, true, false);
        plano("Livre", 1, "simples", 10, true, false);
    }

    private String nome(int centavos) {
        return inferencia.inferirPorValor(centavos).map(Plano::getNome).orElse(null);
    }

    @Test
    void limitesDeCadaFaixa() {
        assertThat(nome(2000)).isEqualTo("Pedido de Oracao");
        assertThat(nome(4999)).isEqualTo("Pedido de Oracao");
        assertThat(nome(5000)).isEqualTo("Oracao + Versiculo");
        assertThat(nome(9999)).isEqualTo("Oracao + Versiculo");
        assertThat(nome(10000)).isEqualTo("Oracao + Profecia");
        assertThat(nome(999999)).isEqualTo("Oracao + Profecia");
    }

    @Test
    void abaixoDeTodasCaiNoMenorVisivelAtivo() {
        assertThat(nome(1)).isEqualTo("Pedido de Oracao");
        assertThat(nome(1999)).isEqualTo("Pedido de Oracao");
    }

    @Test
    void semTierVisivelNaoInfere() {
        jdbc.update("UPDATE plans_plan SET ativo=false WHERE visivel");
        assertThat(inferencia.inferirPorValor(5000)).isEmpty();
    }

    @Test
    void fallbackEhOSimplesInvisivelAtivo() {
        assertThat(inferencia.fallbackValorLivre().map(Plano::getNome)).contains("Livre");
        jdbc.update("UPDATE plans_plan SET ativo=false WHERE nome='Livre'");
        assertThat(inferencia.fallbackValorLivre()).isEmpty();
    }
}
