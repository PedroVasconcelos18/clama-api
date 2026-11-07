package me.clama.api.freemium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.cripto.CriptoConfig;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** As duas tabelas do freemium passam no ddl-auto=validate e os uniques viram excecao tratavel. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BancoDeTeste.class, CriptoConfig.class})
@ActiveProfiles("test")
class FreemiumDadosTest {
    @Autowired FreemiumBlacklistRepository blacklist;
    @Autowired TokenDeConfirmacaoRepository tokens;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    private UUID plano;

    @BeforeEach
    void plano() {
        BancoDeTeste.exigirContainer(jdbc);
        plano = UUID.randomUUID();
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade,
                    ordem, ativo, visivel) values (now(), now(), ?, 'Gratuito', 0, 'd', 'simples_gratuita', 1, true,
                    false)""", plano);
    }

    private FreemiumBlacklist entrada(String cpf, String email, String telefone, String device, String ip) {
        FreemiumBlacklist b = new FreemiumBlacklist();
        b.setCpfHash(cpf);
        b.setEmailHash(email);
        b.setTelefoneHash(telefone);
        b.setDeviceHash(device);
        b.setIpHash(ip);
        return b;
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.setPlano(planos.getReferenceById(plano));
        p.setNome("Maria Silva");
        p.setEmail("maria@exemplo.com");
        p.setCpfCnpj("52998224725");
        p.setValorCentavos(0);
        return pedidos.saveAndFlush(p);
    }

    private TokenDeConfirmacao token(Pedido p, String valor) {
        TokenDeConfirmacao t = new TokenDeConfirmacao();
        t.setToken(valor);
        t.setPedido(p);
        t.setExpiresAt(OffsetDateTime.now().plusHours(24));
        t.setIpOrigem("198.51.100.1");
        t.setDeviceHash("fp-abc");
        return tokens.saveAndFlush(t);
    }

    @Test
    void existeAlgumCasaPorQualquerIdentificador() {
        blacklist.saveAndFlush(entrada("cpf1", "email1", "tel1", "dev1", "ip1"));
        assertThat(blacklist.existeAlgum("cpf1", "x", "x", null)).isTrue();
        assertThat(blacklist.existeAlgum("x", "email1", "x", null)).isTrue();
        assertThat(blacklist.existeAlgum("x", "x", "tel1", null)).isTrue();
        assertThat(blacklist.existeAlgum("x", "x", "x", "dev1")).isTrue();
        assertThat(blacklist.existeAlgum("x", "x", "x", null)).isFalse();
        assertThat(blacklist.existeAlgum("x", "x", "x", "")).isFalse();
        assertThat(blacklist.existeAlgum("x", "x", "x", "outro")).isFalse();
    }

    @Test
    void contarPorIpDesdeUsaLimiteInclusivo() {
        FreemiumBlacklist a = blacklist.saveAndFlush(entrada("c1", "e1", null, null, "ip1"));
        blacklist.saveAndFlush(entrada("c2", "e2", null, null, "ip1"));
        blacklist.saveAndFlush(entrada("c3", "e3", null, null, "ip2"));
        blacklist.saveAndFlush(entrada("c4", "e4", null, null, null));
        assertThat(blacklist.contarPorIpDesde("ip1", a.getCreatedAt())).isEqualTo(2);
        assertThat(blacklist.contarPorIpDesde("ip1", a.getCreatedAt().plusYears(1))).isZero();
        assertThat(blacklist.contarPorIpDesde("ip2", a.getCreatedAt().minusDays(1))).isEqualTo(1);
    }

    @Test
    void uniqueDeCpfEEmailViraExcecaoTratavel() {
        blacklist.saveAndFlush(entrada("cpf1", "email1", null, null, null));
        assertThatThrownBy(() -> blacklist.saveAndFlush(entrada("cpf1", "email2", null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueDeEmailViraExcecaoTratavel() {
        blacklist.saveAndFlush(entrada("cpf1", "email1", null, null, null));
        assertThatThrownBy(() -> blacklist.saveAndFlush(entrada("cpf2", "email1", null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueDoTokenViraExcecaoTratavel() {
        Pedido p = pedido();
        token(p, "tok");
        assertThatThrownBy(() -> token(pedido(), "tok")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tokenGravaInetEDefaults() {
        Pedido p = pedido();
        TokenDeConfirmacao t = token(p, "tok");
        assertThat(jdbc.queryForObject("select host(ip_origem) from freemium_freemiumconfirmationtoken where id=?",
                String.class, t.getId())).isEqualTo("198.51.100.1");
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getUsedAt()).isNull();
        TokenDeConfirmacao lido = tokens.doPedido(p.getId()).orElseThrow();
        assertThat(lido.getToken()).isEqualTo("tok");
        assertThat(lido.getIpOrigem()).isEqualTo("198.51.100.1");
        assertThat(tokens.doPedido(UUID.randomUUID())).isEmpty();
    }

    @Test
    void ipOrigemNuloEhAceito() {
        TokenDeConfirmacao t = new TokenDeConfirmacao();
        t.setToken("tok2");
        t.setPedido(pedido());
        t.setExpiresAt(OffsetDateTime.now().plusHours(24));
        tokens.saveAndFlush(t);
        assertThat(tokens.doPedido(t.getPedido().getId()).orElseThrow().getIpOrigem()).isNull();
        assertThat(t.getDeviceHash()).isEmpty();
    }

    /** select_for_update().select_related("pedido"): a linha do token E a do pedido ficam travadas. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void travarComPedidoTravaOTokenEOPedido() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            UUID pedidoId = tx.execute(st -> token(pedido(), "tok-lock").getPedido().getId());
            tx.executeWithoutResult(st -> {
                TokenDeConfirmacao travado = tokens.travarComPedido("tok-lock").orElseThrow();
                assertThat(travado.getPedido().getId()).isEqualTo(pedidoId);
                assertThat(tokens.travarComPedido("nao-existe")).isEmpty();
                // outra conexao nao consegue travar nenhuma das duas linhas
                assertThat(travaSemEsperar("freemium_freemiumconfirmationtoken", "pedido_id", pedidoId)).isFalse();
                assertThat(travaSemEsperar("orders_pedido", "id", pedidoId)).isFalse();
            });
            // solto o lock, as duas linhas voltam a ser travaveis
            assertThat(travaSemEsperar("freemium_freemiumconfirmationtoken", "pedido_id", pedidoId)).isTrue();
            assertThat(travaSemEsperar("orders_pedido", "id", pedidoId)).isTrue();
        } finally {
            jdbc.update("delete from freemium_freemiumconfirmationtoken");
            jdbc.update("delete from orders_pedido");
            jdbc.update("delete from plans_plan where id = ?", plano);
        }
    }

    private boolean travaSemEsperar(String tabela, String coluna, UUID id) {
        try (Connection c = dataSource.getConnection();
                var ps = c.prepareStatement("select 1 from " + tabela + " where " + coluna + " = ? for update nowait")) {
            ps.setObject(1, id);
            ps.executeQuery();
            return true;
        } catch (SQLException e) {
            assertThat(e.getSQLState()).isEqualTo("55P03"); // lock_not_available
            return false;
        }
    }
}
