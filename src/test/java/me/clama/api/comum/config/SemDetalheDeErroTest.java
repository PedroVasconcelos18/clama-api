package me.clama.api.comum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import javax.sql.DataSource;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** O "Detail: Key (email)=(...)" do Postgres traz PII e o Hibernate loga a mensagem: o driver nao pode inclui-lo. */
@SpringBootTest
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class SemDetalheDeErroTest {
    @Autowired DataSource dataSource;

    @Test
    void violacaoDeUniqueNaoTrazOValor() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("create temp table t_unico (email text unique)");
            st.execute("insert into t_unico values ('pessoa@exemplo.com')");
            assertThatThrownBy(() -> st.execute("insert into t_unico values ('pessoa@exemplo.com')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("duplicate key")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Key (").doesNotContain("pessoa@"));
        }
    }

    @Test
    void dataSourceDeProducaoTambemDesligaODetalhe() {
        var ds = new DatabaseUrlConfig().dataSource("postgres://u:p@localhost:5432/clama");
        assertThat(ds.getDataSourceProperties().getProperty("logServerErrorDetail")).isEqualTo("false");
        ds.close();
    }
}
