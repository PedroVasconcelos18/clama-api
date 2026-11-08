package me.clama.api.instituicao.repasse.comando;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.instituicao.repasse.comando.AuditarRepasses.Achado;
import me.clama.api.instituicao.repasse.comando.AuditarRepasses.Tipo;
import me.clama.api.instituicao.repasse.comando.LedgerDeTeste.Saida;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TestOrfaos, TestOutrosAchados e TestComandoAuditar (test_auditoria_repasse.py), portados: a auditoria enxerga o que o
 * relatorio nao ve (pedido pago sem linha no ledger) e o comando falha com achado critico.
 */
@SpringBootTest
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class AuditarRepassesTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Fernet fernet;
    @Autowired ApplicationContext ctx;
    @Autowired AuditarRepasses auditoria;

    @TempDir Path dir;
    LedgerDeTeste db;

    @BeforeEach
    void limpar() {
        db = new LedgerDeTeste(jdbc, fernet, ctx);
        db.limpar();
    }

    private List<Achado> auditar() {
        return auditoria.auditar(null);
    }

    private List<Achado> doTipo(Tipo tipo) {
        return auditar().stream().filter(a -> a.tipo() == tipo).toList();
    }

    private UUID pedido(UUID inst, String status, int valor, boolean gratuito) {
        return db.pedido(p -> {
            p.instituicao = inst;
            p.status = status;
            p.valor = valor;
            p.gratuito = gratuito;
        });
    }

    private Saida rodar(String... args) {
        return db.rodar(dir, Stream.concat(Stream.of("auditar_repasses"), Arrays.stream(args)).toArray(String[]::new));
    }

    // --- TestOrfaos ---

    @Test
    void pedidoPagoSemRepasseECritico() {
        UUID inst = db.instituicao();
        pedido(inst, "enviada", 5000, false);

        assertThat(doTipo(Tipo.ORFAO)).singleElement().satisfies(a -> {
            assertThat(a.gravidade()).isEqualTo("CRÍTICO");
            assertThat(a.valorEmJogo()).isEqualTo(1000); // 20% de 5000
        });
    }

    /** ERRO entra: o pagamento pode ter passado e a falha ser da geracao. */
    @ParameterizedTest
    @ValueSource(strings = {"pago", "gerando_oracao", "oracao_gerada", "enviada", "aguardando_reenvio", "erro"})
    void todoStatusPosPagamentoConta(String status) {
        pedido(db.instituicao(), status, 5000, false);

        assertThat(auditar()).extracting(Achado::tipo).contains(Tipo.ORFAO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aguardando_pagamento", "aguardando_confirmacao_email"})
    void pedidoQueAindaEsperaNaoEOrfao(String status) {
        pedido(db.instituicao(), status, 5000, false);

        assertThat(auditar()).isEmpty();
    }

    @Test
    void pedidoGratuitoNaoEOrfao() {
        pedido(db.instituicao(), "enviada", 0, true);

        assertThat(auditar()).isEmpty();
    }

    @Test
    void pedidoSemInstituicaoNaoEOrfao() {
        pedido(null, "enviada", 5000, false);

        assertThat(auditar()).isEmpty();
    }

    @Test
    void pedidoComRepasseNaoEOrfao() {
        UUID inst = db.instituicao();
        UUID pedido = pedido(inst, "enviada", 5000, false);
        db.repasse(inst, r -> { r.pedido = pedido; r.base = 5000; r.valor = 1000; });

        assertThat(auditar()).isEmpty();
    }

    // --- TestOutrosAchados ---

    private UUID repasse(UUID inst, String status, String mp, int base, int valor) {
        UUID pedido = db.pedido(p -> {
            p.instituicao = inst;
            p.status = status;
            p.valor = 5000;
            p.mp = mp;
        });
        db.repasse(inst, r -> { r.pedido = pedido; r.base = base; r.valor = valor; });
        return pedido;
    }

    @Test
    void calculoForaDoPercentualECritico() {
        repasse(db.instituicao(), "enviada", "MP-1", 5000, 4000); // deveria ser 1000

        assertThat(doTipo(Tipo.CALCULO_ERRADO)).singleElement().satisfies(a -> {
            assertThat(a.gravidade()).isEqualTo("CRÍTICO");
            assertThat(a.valorEmJogo()).isEqualTo(3000);
            assertThat(a.detalhe()).isEqualTo("ledger diz 4000 centavos; 20% de 5000 dá 1000");
        });
    }

    @Test
    void repasseDePedidoNaoPagoECritico() {
        repasse(db.instituicao(), "aguardando_pagamento", "MP-1", 5000, 1000);

        assertThat(doTipo(Tipo.PEDIDO_NAO_PAGO)).singleElement().extracting(Achado::gravidade).isEqualTo("CRÍTICO");
    }

    /** Pedido convertido em gratuito depois do repasse: ledger certo, ruido esperado. */
    @Test
    void divergenciaDeBaseESoAtencao() {
        UUID pedido = repasse(db.instituicao(), "pago", "MP-1", 5000, 1000);
        db.marcarComoGratuito(pedido);

        assertThat(doTipo(Tipo.DIVERGENCIA_DE_BASE)).first().extracting(Achado::gravidade).isEqualTo("ATENÇÃO");
    }

    @Test
    void semIdDePagamentoEAtencao() {
        repasse(db.instituicao(), "enviada", null, 5000, 1000);

        assertThat(doTipo(Tipo.SEM_ID_DE_PAGAMENTO)).singleElement().extracting(Achado::gravidade)
                .isEqualTo("ATENÇÃO");
    }

    @Test
    void ledgerSaudavelNaoGeraAchado() {
        repasse(db.instituicao(), "enviada", "MP-1", 5000, 1000);

        assertThat(auditar()).isEmpty();
    }

    @Test
    void filtraPorInstituicao() {
        UUID alvo = db.instituicao();
        UUID outra = db.instituicao();
        pedido(outra, "enviada", 5000, false);

        assertThat(auditoria.auditar(alvo)).isEmpty();
        assertThat(auditoria.auditar(outra)).hasSize(1);
    }

    // --- TestComandoAuditar ---

    @Test
    void falhaQuandoHaAchadoCritico() {
        pedido(db.instituicao(), "enviada", 5000, false);

        Saida s = rodar();

        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).isEqualTo("CommandError: 1 achado(s) crítico(s), R$ 10,00 em jogo.\n");
    }

    @Test
    void ledgerLimpoPassa() {
        db.instituicao();

        Saida s = rodar("--csv", "achados.csv");

        assertThat(s.exit()).isZero();
        assertThat(s.out()).contains("Ledger consistente");
        assertThat(dir.resolve("achados.csv")).doesNotExist(); // D8: paridade, sem achado nao grava
    }

    @Test
    void gravaCsvDosAchados() throws Exception {
        pedido(db.instituicao(), "enviada", 5000, false);
        Path saida = dir.resolve("achados.csv");

        assertThat(rodar("--csv=" + saida).exit()).isEqualTo(1);

        assertThat(Files.readString(saida, UTF_8)).startsWith("﻿Gravidade;").contains("orfao", "CRÍTICO");
    }
}
