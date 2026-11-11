package me.clama.pedido.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import me.clama.TesteDeRepositorio;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@TesteDeRepositorio
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
		assertThat(coluna(p.getId(), "email_hmac")).isEqualTo(PedidoTest.HMAC.email("maria@exemplo.com"));
		assertThat(coluna(p.getId(), "cpf_hmac")).isEqualTo(PedidoTest.HMAC.cpf("52998224725"));
		assertThat(coluna(p.getId(), "consentimento_versao")).isEqualTo("2026-01");
	}

	@Test
	void oracaoTambemCifrada() {
		var p = PedidoTest.novo(plano(), null);
		p.confirmarPagamento(PedidoTest.AGORA);
		p.iniciarGeracao(PedidoTest.AGORA);
		p.registrarOracao("Senhor, abencoa.", PedidoTest.AGORA);
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
		lido.confirmarPagamento(PedidoTest.AGORA);
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
		var p = Pedido.novo(new Pedido.Dados(987654L, plano(), null, 100, "A", "a@a.com", null, null, null, null, "t", java.time.Instant.now(), null, "2026-01"), PedidoTest.HMAC, PedidoTest.AGORA);
		pedidos.saveAndFlush(p);
		jdbc.update("delete from usuario where id = 987654");
		em.clear();
		assertThat(pedidos.findById(p.getId()).orElseThrow().getUsuarioId()).isNull();
	}

	@Test
	void buscaPorHmacDoEmail() {
		var p = salvar();
		assertThat(pedidos.findByEmailHmac(PedidoTest.HMAC.email("maria@exemplo.com"))).extracting(Pedido::getId).containsExactly(p.getId());
		assertThat(pedidos.findByEmailHmac("outro")).isEmpty();
	}

	@Test
	void paradosListaSoOsEmAndamentoAntigos() {
		var paga = PedidoTest.novo(plano(), null);
		paga.confirmarPagamento(PedidoTest.AGORA);
		pedidos.saveAndFlush(paga);
		var aguardando = salvar();
		jdbc.update("update pedido set atualizado_em = now() - interval '1 hour'");
		var recente = PedidoTest.novo(plano(), null);
		recente.confirmarPagamento(PedidoTest.AGORA);
		pedidos.saveAndFlush(recente);
		jdbc.update("update pedido set atualizado_em = now() where id = ?", recente.getId());
		var ids = pedidos.parados(java.time.Instant.now().minusSeconds(1200));
		assertThat(ids).containsExactly(paga.getId());
		assertThat(aguardando.getStatus()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO);
	}

	@Test
	void semCpfGravaCpfHmacNulo() {
		var p = pedidos.saveAndFlush(Pedido.novo(new Pedido.Dados(null, plano(), null, 100, "A", "a@a.com", null, null, null, null, "t",
				PedidoTest.AGORA, null, "2026-01"), PedidoTest.HMAC, PedidoTest.AGORA));
		assertThat(coluna(p.getId(), "cpf_hmac")).isNull();
	}

	@Test
	void trocarOEmailMudaOHmacGravado() {
		var p = salvar();
		var antes = coluna(p.getId(), "email_hmac");
		p.alterarEmail("outra@exemplo.com", PedidoTest.HMAC);
		pedidos.saveAndFlush(p);
		assertThat(coluna(p.getId(), "email_hmac")).isNotEqualTo(antes).isEqualTo(PedidoTest.HMAC.email("outra@exemplo.com"));
	}

	@Test
	void idadeNegativaViolaCheck() {
		var p = salvar();
		assertThatThrownBy(() -> jdbc.update("update pedido set idade = -1 where id = ?", p.getId())).isInstanceOf(DataIntegrityViolationException.class);
	}
}
