package me.clama.pedido.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ BancoDeTeste.class, me.clama.CriptoDeTeste.class })
class PedidoRepositoryTest {

	@Autowired PedidoRepository pedidos;
	@Autowired JdbcTemplate jdbc;
	@Autowired jakarta.persistence.EntityManager em;

	/** O @DataJpaTest desfaz tudo no fim (transacao do teste); limpar no @AfterEach falharia apos violar um CHECK. */
	@BeforeEach
	void limpar() {
		jdbc.update("delete from pedido");
	}

	private Long plano() {
		return jdbc.queryForObject("select id from plano where tipo = 'VALOR_LIVRE'", Long.class);
	}

	private Pedido salvar() {
		return pedidos.saveAndFlush(PedidoTest.novo(plano(), null));
	}

	private String coluna(UUID id, String nome) {
		return jdbc.queryForObject("select " + nome + " from pedido where id = ?", String.class, id);
	}

	@Test
	void piiFicaCifradaNoBancoELeituraDevolveOClaro() {
		var p = salvar();
		em.clear();
		for (var c : new String[] { "nome", "email", "cpf_cnpj", "telefone", "pedido_oracao" })
			assertThat(coluna(p.getId(), c)).as(c).startsWith("v1:").doesNotContain("Maria").doesNotContain("maria@");
		var lido = pedidos.findById(p.getId()).orElseThrow();
		assertThat(lido.getNome()).isEqualTo("Maria");
		assertThat(lido.getEmail()).isEqualTo("maria@exemplo.com");
		assertThat(lido.getCpfCnpj()).isEqualTo("52998224725");
		assertThat(lido.getTelefone()).isEqualTo("11999990000");
		assertThat(lido.getPedidoOracao()).isEqualTo("Pela minha familia");
		// idade e sexo ficam em claro
		assertThat(coluna(p.getId(), "sexo")).isEqualTo("F");
		assertThat(coluna(p.getId(), "email_hmac")).isEqualTo("hm");
	}

	@Test
	void oracaoTambemCifrada() {
		var p = PedidoTest.novo(plano(), null);
		p.confirmarPagamento();
		p.iniciarGeracao();
		p.registrarOracao("Senhor, abencoa.");
		pedidos.saveAndFlush(p);
		em.clear();
		assertThat(coluna(p.getId(), "oracao")).startsWith("v1:").doesNotContain("Senhor");
		assertThat(pedidos.findById(p.getId()).orElseThrow().getOracao()).isEqualTo("Senhor, abencoa.");
	}

	@Test
	void mudarSoOStatusNaoRegravaAsColunasCifradas() {
		var p = salvar();
		var antes = jdbc.queryForMap("select nome, email, cpf_cnpj, telefone, pedido_oracao from pedido where id = ?", p.getId());
		em.clear();
		var lido = pedidos.findById(p.getId()).orElseThrow();
		lido.confirmarPagamento();
		pedidos.saveAndFlush(lido);
		var depois = jdbc.queryForMap("select nome, email, cpf_cnpj, telefone, pedido_oracao from pedido where id = ?", p.getId());
		assertThat(depois).isEqualTo(antes);
		assertThat(coluna(p.getId(), "status")).isEqualTo("PAGO");
	}

	@Test
	void travarDevolveOPedidoEOutroIdVazio() {
		var p = salvar();
		assertThat(pedidos.travar(p.getId())).isPresent();
		assertThat(pedidos.travar(UUID.randomUUID())).isEmpty();
	}

	@Test
	void statusForaDoEnumViolaCheck() {
		var p = salvar();
		assertThatThrownBy(() -> jdbc.update("update pedido set status = 'aguardando_reenvio' where id = ?", p.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void gratuitoComValorViolaCheck() {
		var p = salvar();
		assertThatThrownBy(() -> jdbc.update("update pedido set gratuito = true where id = ?", p.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void usuarioApagadoSoltaOVinculo() {
		jdbc.update("insert into usuario (id, email, email_canonico, nome_completo, papel, ativo, criado_em) values (987654, 'x@x.com', 'x@x.com', 'X', 'CLIENTE', true, now())");
		var p = Pedido.novo(new Pedido.Dados(987654L, plano(), null, 100, "A", "a@a.com", "h", null, null, null, null, null, "t",
				java.time.Instant.now(), null));
		pedidos.saveAndFlush(p);
		jdbc.update("delete from usuario where id = 987654");
		em.clear();
		assertThat(pedidos.findById(p.getId()).orElseThrow().getUsuarioId()).isNull();
	}

	@Test
	void buscaPorHmacDoEmail() {
		var p = salvar();
		assertThat(pedidos.findByEmailHmac("hm")).extracting(Pedido::getId).containsExactly(p.getId());
		assertThat(pedidos.findByEmailHmac("outro")).isEmpty();
	}

	@Test
	void paradosListaSoOsEmAndamentoAntigos() {
		var paga = PedidoTest.novo(plano(), null);
		paga.confirmarPagamento();
		pedidos.saveAndFlush(paga);
		var aguardando = salvar();
		jdbc.update("update pedido set atualizado_em = now() - interval '1 hour'");
		var recente = PedidoTest.novo(plano(), null);
		recente.confirmarPagamento();
		pedidos.saveAndFlush(recente);
		var ids = pedidos.parados(java.time.Instant.now().minusSeconds(1200)).stream().map(Pedido::getId).toList();
		assertThat(ids).containsExactly(paga.getId());
		assertThat(aguardando.getStatus()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO);
	}
}
