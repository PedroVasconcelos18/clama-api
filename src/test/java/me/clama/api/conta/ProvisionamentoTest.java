package me.clama.api.conta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.oracao.SenhasTemporarias;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** provisionar_ou_vincular_conta_doador (customers/services/account_provisioning.py), chamado so pelo webhook. */
@SpringBootTest
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class ProvisionamentoTest {
    static final String ALFABETO = "[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789]{14}";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    PedidoRepository pedidos;

    @Autowired
    PlanoRepository planos;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    SenhasTemporarias senhas;

    @Autowired
    ProvisionamentoDeConta provisionamento;

    final SenhaDjango hashes = new SenhaDjango();
    UUID plano;

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE users_user, orders_pedido, plans_plan RESTART IDENTITY CASCADE");
        senhas.limpar();
        plano = UUID.randomUUID();
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem,
                    ativo, visivel) values (now(), now(), ?, 'Pedido de Oração', 2000, 'd', 'simples', 1, true, true)""",
                plano);
    }

    UUID pedido(String email) {
        return tx.execute(st -> {
            Pedido p = new Pedido();
            p.setPlano(planos.getReferenceById(plano));
            p.setNome("Maria Silva");
            p.setEmail(email);
            p.setCpfCnpj("52998224725");
            p.setTelefone("(11) 99999-8888");
            p.setIdade(35);
            p.setSexo("feminino");
            p.setValorCentavos(3000);
            return pedidos.saveAndFlush(p).getId();
        });
    }

    ProvisionamentoDeConta.Conta provisionar(UUID id) {
        return tx.execute(st -> provisionamento.provisionarOuVincular(pedidos.travar(id).orElseThrow()));
    }

    Long dono(UUID pedido) {
        return jdbc.queryForObject("select user_id from orders_pedido where id = ?", Long.class, pedido);
    }

    @Test
    void provisionamentoGmailCanonico() {
        UUID a = pedido(" Maria.Silva+doacao@GoogleMail.com ");
        ProvisionamentoDeConta.Conta conta = provisionar(a);

        assertThat(conta.criada()).isTrue();
        Usuario u = usuarios.findById(conta.usuario().getId()).orElseThrow();
        assertThat(u.getEmail()).as("o canonico vai para users_user.email").isEqualTo("mariasilva@gmail.com");
        assertThat(u.isForceChangePassword()).isTrue();
        assertThat(u.getNomeCompleto()).isEqualTo("Maria Silva");
        assertThat(u.getCpfCnpj()).isEqualTo("52998224725");
        assertThat(u.getTelefone()).isEqualTo("(11) 99999-8888");
        assertThat(u.getIdade()).isEqualTo(35);
        assertThat(u.getSexo()).isEqualTo("feminino");
        String senha = senhas.ler("doador:temp_password:" + u.getId()).orElseThrow();
        assertThat(senha).matches(ALFABETO);
        assertThat(hashes.matches(senha, u.getPassword())).as("a senha do cache e a da conta").isTrue();
        assertThat(dono(a)).isEqualTo(u.getId());

        // outra grafia do mesmo Gmail: acha pelo e-mail canonico/hash e so vincula, sem senha nova
        senhas.limpar();
        UUID b = pedido("m.a.r.i.a.silva@gmail.com");
        ProvisionamentoDeConta.Conta outra = provisionar(b);
        assertThat(outra.criada()).isFalse();
        assertThat(outra.usuario().getId()).isEqualTo(u.getId());
        assertThat(dono(b)).isEqualTo(u.getId());
        assertThat(senhas.ler("doador:temp_password:" + u.getId())).isEmpty();
        assertThat(usuarios.count()).isEqualTo(1);
        assertThat(usuarios.findById(u.getId()).orElseThrow().getPassword()).as("senha intocada")
                .isEqualTo(u.getPassword());
    }

    @Test
    void vinculaSemCaixaENaoMexeEmPedidoJaVinculado() {
        Usuario ana = new Usuario();
        ana.setEmail("Ana@Exemplo.com");
        ana.setPassword(hashes.encode("Clama-forte-2026"));
        ana = usuarios.saveAndFlush(ana);

        UUID a = pedido("ana@exemplo.com");
        ProvisionamentoDeConta.Conta conta = provisionar(a);
        assertThat(conta.criada()).isFalse();
        assertThat(conta.usuario().getId()).isEqualTo(ana.getId());
        assertThat(dono(a)).isEqualTo(ana.getId());

        // ja vinculado: no-op, mesmo com outro e-mail no pedido
        jdbc.update("update orders_pedido set email = ? where id = ?", "outra@exemplo.com", a);
        assertThat(provisionar(a).usuario().getId()).isEqualTo(ana.getId());

        assertThat(provisionar(pedido("")).usuario()).as("sem e-mail: nada").isNull();
        assertThat(usuarios.count()).isEqualTo(1);
    }

    /** Duas doacoes do mesmo e-mail confirmadas ao mesmo tempo: uma conta so, os dois pedidos nela. */
    @Test
    void corridaDeEmailUnico() throws Exception {
        UUID a = pedido("joao@exemplo.com");
        UUID b = pedido("Joao@Exemplo.com");
        CyclicBarrier largada = new CyclicBarrier(2);
        List<ProvisionamentoDeConta.Conta> contas;
        try (var threads = Executors.newFixedThreadPool(2)) {
            List<Future<ProvisionamentoDeConta.Conta>> f = List.of(a, b).stream().map(id -> threads.submit(
                    (Callable<ProvisionamentoDeConta.Conta>) () -> tx.execute(st -> {
                        Pedido p = pedidos.travar(id).orElseThrow();
                        try {
                            largada.await(); // os dois passam do lookup antes de o 1o criar
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return provisionamento.provisionarOuVincular(p);
                    }))).toList();
            contas = List.of(f.get(0).get(), f.get(1).get());
        }

        assertThat(contas).filteredOn(ProvisionamentoDeConta.Conta::criada).hasSize(1);
        Long id = contas.get(0).usuario().getId();
        assertThat(contas.get(1).usuario().getId()).isEqualTo(id);
        assertThat(usuarios.count()).isEqualTo(1);
        assertThat(dono(a)).isEqualTo(id);
        assertThat(dono(b)).isEqualTo(id);
    }

    /** Sem transacao o vinculo nunca seria gravado e a senha ficaria sem dono: recusa, como o Fila.enfileirar. */
    @Test
    void exigeTransacao() {
        Pedido p = pedidos.findById(pedido("sem-tx@exemplo.com")).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provisionamento.provisionarOuVincular(p))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transacao");
        assertThat(usuarios.count()).isZero();
    }
}
