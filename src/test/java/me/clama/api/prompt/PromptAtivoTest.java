package me.clama.api.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class PromptAtivoTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PromptAtivo promptAtivo;

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("delete from prompts_prompttemplate");
    }

    private UUID inserir(String nome, int versao) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into prompts_prompttemplate (id, created_at, updated_at, nome, versao, system_prompt,"
                + " instrucoes_por_complexidade, ativo) values (?, now(), now(), ?, ?, 's', '{}', true)", id, nome, versao);
        return id;
    }

    @Test
    void nenhumAtivoLancaSemPromptAtivo() {
        assertThatThrownBy(promptAtivo::obter).isInstanceOf(PromptAtivo.SemPromptAtivo.class);
    }

    @Test
    void umAtivoRetornaOTemplate() {
        UUID a = inserir("ativo-a", 1);
        assertThat(promptAtivo.obter().getId()).isEqualTo(a);
    }

    @Test
    void maisDeUmLancaMaisDeUmPromptAtivoSemEmbrulho() {
        inserir("ativo-a", 1);
        inserir("ativo-b", 2);
        assertThatThrownBy(promptAtivo::obter).isExactlyInstanceOf(PromptAtivo.MaisDeUmPromptAtivo.class);
    }
}
