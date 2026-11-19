package me.clama.pedido.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.pedido.GeracaoSolicitada;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.ReenvioSolicitado;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.TransicaoInvalidaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(BancoDeTeste.class)
@RecordApplicationEvents
class PedidoApiTest {

	@Autowired PedidoApi api;
	@Autowired PedidoRepository pedidos;
	@Autowired UsuarioRepository usuarios;
	@Autowired JdbcTemplate jdbc;
	@Autowired TransactionTemplate tx;
	@Autowired ApplicationEvents eventos;

	@BeforeEach @AfterEach
	void limpar() {
		jdbc.update(me.clama.BancoDeTeste.LIMPAR_FILA); // GeracaoSolicitada enfileira gerar-oracao e comita
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where email like 'doador-%'");
	}

	private Pedido novo(String email) {
		return pedidos.saveAndFlush(Pedido.novo(new Pedido.Dados(null, planoId(), null, 2000, "Maria Souza", email, "52998224725", null, null, null, "Pela familia", Instant.now(), "203.0.113.9", "2026-01", null), PedidoTest.HMAC, Instant.now()));
	}

	private Long planoId() {
		return jdbc.queryForObject("select id from plano where tipo = 'VALOR_LIVRE'", Long.class);
	}

	@Test
	void confirmarPagamentoVinculaContaEPublicaGeracaoSolicitada() {
		var email = "doador-" + UUID.randomUUID() + "@exemplo.com";
		var id = novo(email).getId();
		assertThat(api.confirmarPagamento(id)).isTrue();
		var p = pedidos.findById(id).orElseThrow();
		assertThat(p.getStatus()).isEqualTo(StatusPedido.PAGO);
		assertThat(p.getUsuarioId()).isNotNull();
		assertThat(usuarios.findByEmail(email)).isPresent();
		assertThat(eventos.stream(GeracaoSolicitada.class)).extracting(GeracaoSolicitada::pedidoId).containsExactly(id);
	}

	/**
	 * Regressao: ler o pedido e depois travar na mesma transacao. Sem {@code @Version}, o {@code FOR UPDATE} devolveria a
	 * instancia velha do contexto e confirmaria de novo um pedido que outra transacao ja pagou.
	 */
	@Test
	void lerAntesDeTravarNaMesmaTransacaoEnxergaOEstadoAtual() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		var confirmou = tx.execute(s -> {
			assertThat(api.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO); // fica no contexto
			var outra = new Thread(() -> jdbc.update("update pedido set status = 'PAGO' where id = ?", id)); // commit proprio
			outra.start();
			try {
				outra.join();
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			return api.confirmarPagamento(id);
		});
		assertThat(confirmou).isFalse();
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
	}

	@Test
	void confirmarPagamentoRepetidoNaoFazNadaEDevolveFalse() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		eventos.clear();
		assertThat(api.confirmarPagamento(id)).isFalse();
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
	}

	@Test
	void pedidoDeContaLogadaMantemOVinculo() {
		var u = tx.execute(s -> usuarios.saveAndFlush(me.clama.conta.interno.Usuario.novo("doador-x-" + UUID.randomUUID() + "@exemplo.com", "Fulano", me.clama.conta.interno.Papel.CLIENTE, Instant.now())));
		var p = pedidos.saveAndFlush(Pedido.novo(new Pedido.Dados(u.getId(), planoId(), null, 2000, "Fulano", "outro-" + UUID.randomUUID() + "@exemplo.com", null, null, null, null, "t", Instant.now(), null, "2026-01", null), PedidoTest.HMAC, Instant.now()));
		api.confirmarPagamento(p.getId());
		assertThat(pedidos.findById(p.getId()).orElseThrow().getUsuarioId()).isEqualTo(u.getId());
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where id = ?", u.getId());
	}

	@Test
	void idInexistenteVira404() {
		assertThatThrownBy(() -> api.confirmarPagamento(UUID.randomUUID())).isInstanceOf(PedidoNaoEncontradoException.class);
		assertThat(api.resumo(UUID.randomUUID())).isEmpty();
	}

	@Test
	void resumoEDadosParaOracao() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		var r = api.resumo(id).orElseThrow();
		assertThat(r.status()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO);
		assertThat(r.valorCentavos()).isEqualTo(2000);
		var d = api.dadosParaOracao(id);
		assertThat(d.nome()).isEqualTo("Maria Souza");
		assertThat(d.pedidoOracao()).isEqualTo("Pela familia");
		assertThat(d.oracao()).isNull();
	}

	@Test
	void ultimoErroApareceNoResumoENosDadosELimpaAoRegistrarOracao() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		assertThat(api.resumo(id).orElseThrow().ultimoErro()).isNull();
		api.confirmarPagamento(id);
		api.iniciarGeracao(id);
		api.agendarNovaTentativa(id, "timeout");
		assertThat(api.resumo(id).orElseThrow().ultimoErro()).isEqualTo("timeout");
		assertThat(api.dadosParaOracao(id).ultimoErro()).isEqualTo("timeout");
		api.iniciarGeracao(id);
		api.registrarOracao(id, "Senhor...");
		assertThat(api.resumo(id).orElseThrow().ultimoErro()).isNull();
		assertThat(api.dadosParaOracao(id).ultimoErro()).isNull();
	}

	@Test
	void cicloDeGeracaoPelaApi() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		api.iniciarGeracao(id);
		api.agendarNovaTentativa(id, "timeout");
		assertThat(api.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_NOVA_TENTATIVA);
		api.iniciarGeracao(id);
		api.registrarOracao(id, "Senhor...");
		assertThat(api.dadosParaOracao(id).oracao()).isEqualTo("Senhor...");
		api.marcarEnviado(id);
		assertThat(api.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.ENVIADA);
	}

	@Test
	void falharEComandosDoAdminPublicamEventos() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		api.falhar(id, "credit_balance");
		assertThat(pedidos.findById(id).orElseThrow().getUltimoErro()).isEqualTo("credit_balance");
		eventos.clear();
		api.solicitarRegeneracao(id);
		assertThat(eventos.stream(GeracaoSolicitada.class)).hasSize(1);
		api.iniciarGeracao(id);
		api.registrarOracao(id, "x");
		api.marcarEnviado(id);
		api.solicitarReenvio(id);
		assertThat(eventos.stream(ReenvioSolicitado.class)).extracting(ReenvioSolicitado::pedidoId).containsExactly(id);
	}

	@Test
	void concederGratuidadePublicaGeracao() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.concederGratuidade(id);
		assertThat(eventos.stream(GeracaoSolicitada.class)).hasSize(1);
		assertThat(pedidos.findById(id).orElseThrow().isGratuito()).isTrue();
	}

	@Test
	void transicaoInvalidaPelaApiNaoPublicaEvento() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		assertThatThrownBy(() -> api.iniciarGeracao(id)).isInstanceOf(TransicaoInvalidaException.class);
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
	}

	@Test
	void travarParaCobrancaExigeTransacao() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		assertThatThrownBy(() -> api.travarParaCobranca(id)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
		var c = tx.execute(s -> api.travarParaCobranca(id));
		assertThat(c.valorCentavos()).isEqualTo(2000);
		assertThat(c.cpfCnpj()).isEqualTo("52998224725");
		assertThat(c.email()).startsWith("doador-");
	}

	@Test
	void emAndamentoParadosPelaJanela() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		assertThat(api.emAndamentoParados(Duration.ofMinutes(20))).isEmpty();
		jdbc.update("update pedido set atualizado_em = now() - interval '30 minutes'");
		assertThat(api.emAndamentoParados(Duration.ofMinutes(20))).containsExactly(id);
	}

	@Test
	void falharSeParadoSoFalhaOQueNaoAndou() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		jdbc.update("update pedido set atualizado_em = now() - interval '30 minutes'");
		var corte = Instant.now().minus(Duration.ofMinutes(20));
		assertThat(api.emAndamentoParados(Duration.ofMinutes(20))).containsExactly(id);
		// entre a listagem e a falha, o pedido andou
		api.iniciarGeracao(id);
		assertThat(api.falharSeParado(id, corte, "pedido_preso")).isFalse();
		assertThat(api.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.GERANDO_ORACAO);
		// parado de verdade: falha com o codigo
		jdbc.update("update pedido set atualizado_em = now() - interval '30 minutes'");
		assertThat(api.falharSeParado(id, corte, "pedido_preso")).isTrue();
		assertThat(pedidos.findById(id).orElseThrow().getUltimoErro()).isEqualTo("pedido_preso");
		// ja em ERRO: nao e mais elegivel
		jdbc.update("update pedido set atualizado_em = now() - interval '30 minutes'");
		assertThat(api.falharSeParado(id, corte, "pedido_preso")).isFalse();
	}

	// ---- freemium ----

	private static me.clama.pedido.NovoPedido corpoFreemium(String email, String cpf) {
		return new me.clama.pedido.NovoPedido("Ana Freemium", email, cpf, null, null, null, "Paz", null, null, null, true, null, null);
	}

	private UUID freemium(String email) {
		return api.criarFreemium(corpoFreemium(email, "529.982.247-25"), "203.0.113.9");
	}

	@Test
	void criarFreemiumNasceAguardandoSemContaSemEventoEComIpNoConsentimento() {
		var email = "doador-" + UUID.randomUUID() + "@exemplo.com";
		var id = freemium(email);
		var p = pedidos.findById(id).orElseThrow();
		assertThat(p.getStatus()).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(p.isGratuito()).isTrue();
		assertThat(p.getValorCentavos()).isZero();
		assertThat(p.getUsuarioId()).isNull();
		assertThat(p.getCpfCnpj()).isEqualTo("52998224725");
		assertThat(p.getConsentimentoIp()).isEqualTo("203.0.113.9");
		assertThat(p.getPlanoId()).isEqualTo(jdbc.queryForObject("select id from plano where tipo = 'GRATUITO'", Long.class));
		assertThat(usuarios.findByEmail(email)).isEmpty();
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
		assertThat(jdbc.queryForList("select task_instance from scheduled_tasks", String.class)).containsExactly("triar-pedido/" + id); // so a triagem
	}

	@Test
	void confirmarEmailCriaContaPublicaGeracaoEDevolveIdentificadores() {
		var email = "doador-" + UUID.randomUUID() + "@exemplo.com";
		var id = freemium(email);
		var ids = tx.execute(s -> api.confirmarEmail(id));
		var p = pedidos.findById(id).orElseThrow();
		assertThat(p.getStatus()).isEqualTo(StatusPedido.PAGO);
		assertThat(p.getUsuarioId()).isNotNull();
		assertThat(usuarios.findByEmail(email)).isPresent();
		assertThat(eventos.stream(GeracaoSolicitada.class)).extracting(GeracaoSolicitada::pedidoId).containsExactly(id);
		assertThat(ids.emailHmac()).isEqualTo(PedidoTest.HMAC.email(email));
		assertThat(ids.cpfHmac()).isEqualTo(PedidoTest.HMAC.cpf("52998224725"));
		assertThat(ids.ip()).isEqualTo(PedidoTest.HMAC.ip("203.0.113.9")).hasSize(64);
	}

	@Test
	void confirmarEmailVinculaContaQueJaExistePeloCanonico() {
		var base = "doador-" + UUID.randomUUID().toString().replace("-", "");
		var existente = tx.execute(s -> usuarios.saveAndFlush(me.clama.conta.interno.Usuario.novo(base + "@gmail.com", "Dona", me.clama.conta.interno.Papel.CLIENTE, Instant.now())));
		var id = freemium(base.substring(0, 10) + "." + base.substring(10) + "+oi@gmail.com");
		tx.executeWithoutResult(s -> api.confirmarEmail(id));
		assertThat(pedidos.findById(id).orElseThrow().getUsuarioId()).isEqualTo(existente.getId());
		assertThat(jdbc.queryForObject("select count(*) from usuario where email_canonico like ?", Integer.class, base + "%")).isEqualTo(1);
	}

	@Test
	void confirmarEmailForaDoEstadoEhTransicaoInvalidaSemEvento() {
		var id = freemium("doador-" + UUID.randomUUID() + "@exemplo.com");
		tx.executeWithoutResult(s -> api.confirmarEmail(id));
		eventos.clear();
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.confirmarEmail(id))).isInstanceOf(TransicaoInvalidaException.class);
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
		var pago = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.confirmarEmail(pago))).isInstanceOf(TransicaoInvalidaException.class);
		assertThat(pedidos.findById(pago).orElseThrow().getUsuarioId()).isNull();
	}

	@Test
	void pendenteDentroEForaDaJanelaPorEmailOuCpf() {
		var email = "doador-" + UUID.randomUUID() + "@exemplo.com";
		var id = freemium(email);
		var h = PedidoTest.HMAC;
		var ha24 = Instant.now().minus(Duration.ofHours(24));
		assertThat(api.existePendenteDeConfirmacao(h.email(email), h.cpf("1"), ha24)).isTrue(); // so o e-mail casa
		assertThat(api.existePendenteDeConfirmacao(h.email("outro@exemplo.com"), h.cpf("529.982.247-25"), ha24)).isTrue(); // so o CPF
		assertThat(api.existePendenteDeConfirmacao(h.email("outro@exemplo.com"), null, ha24)).isFalse();
		assertThat(api.existePendenteDeConfirmacao(h.email(email), h.cpf("529.982.247-25"), Instant.now().plus(Duration.ofMinutes(1)))).isFalse(); // criado antes da janela
		jdbc.update("update pedido set criado_em = now() - interval '25 hours' where id = ?", id);
		assertThat(api.existePendenteDeConfirmacao(h.email(email), h.cpf("529.982.247-25"), ha24)).isFalse();
		jdbc.update("update pedido set criado_em = now(), status = 'CONFIRMACAO_EXPIRADA' where id = ?", id);
		assertThat(api.existePendenteDeConfirmacao(h.email(email), h.cpf("529.982.247-25"), ha24)).as("so pendente conta").isFalse();
	}

	/** Janela inclusiva: um pedido criado exatamente em {@code desde} conta; um microssegundo depois, nao. */
	@Test
	void pendenteNoLimiteExatoDaJanelaConta() {
		var email = "doador-" + UUID.randomUUID() + "@exemplo.com";
		var id = freemium(email);
		var criadoEm = jdbc.queryForObject("select criado_em from pedido where id = ?", java.time.OffsetDateTime.class, id).toInstant();
		var h = PedidoTest.HMAC;
		assertThat(api.existePendenteDeConfirmacao(h.email(email), null, criadoEm)).as("criado_em == desde").isTrue();
		assertThat(api.existePendenteDeConfirmacao(h.email(email), null, criadoEm.plus(java.time.temporal.ChronoUnit.MICROS.getDuration()))).isFalse();
		assertThat(api.existePendenteDeConfirmacao(h.email(email), null, criadoEm.minus(java.time.temporal.ChronoUnit.MICROS.getDuration()))).isTrue();
	}

	@Test
	void criarFreemiumSemPlanoGratuitoDa503SemPedido() {
		jdbc.update("delete from plano where tipo = 'GRATUITO'");
		try {
			assertThatThrownBy(() -> freemium("doador-" + UUID.randomUUID() + "@exemplo.com"))
					.isInstanceOfSatisfying(me.clama.infra.web.ErroDeNegocio.class, e -> {
						assertThat(e.codigo()).isEqualTo("freemium_indisponivel");
						assertThat(e.status()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
					});
			assertThat(jdbc.queryForObject("select count(*) from pedido", Integer.class)).isZero();
		} finally {
			jdbc.update("""
					insert into plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
				values ('Gratuito', 'Sua primeira oração — por nossa conta', 0, 'SIMPLES', 'GRATUITO', 1000)
				on conflict (tipo) where tipo <> 'COMUM' do nothing""");
		}
	}

	@Test
	void cpfTemContaSoComDono() {
		var cpf = PedidoTest.HMAC.cpf("529.982.247-25");
		var id = freemium("doador-" + UUID.randomUUID() + "@exemplo.com");
		assertThat(api.cpfTemConta(cpf)).as("pendente nao tem dono").isFalse();
		tx.executeWithoutResult(s -> api.confirmarEmail(id));
		assertThat(api.cpfTemConta(cpf)).isTrue();
		assertThat(api.cpfTemConta(PedidoTest.HMAC.cpf("111.444.777-35"))).isFalse();
		assertThat(api.cpfTemConta(null)).isFalse();
	}

	@Test
	void expirarConfirmacoesSoTocaPendenteAntigo() {
		var velho = freemium("doador-" + UUID.randomUUID() + "@exemplo.com");
		var recente = freemium("doador-" + UUID.randomUUID() + "@exemplo.com");
		var confirmado = freemium("doador-" + UUID.randomUUID() + "@exemplo.com");
		var pago = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		tx.executeWithoutResult(s -> api.confirmarEmail(confirmado));
		jdbc.update("update pedido set criado_em = now() - interval '49 hours' where id in (?, ?, ?)", velho, confirmado, pago);
		var corte = Instant.now().minus(Duration.ofHours(48));
		assertThat(api.contarConfirmacoesVencidas(corte)).isEqualTo(1);
		assertThat(api.expirarConfirmacoes(corte)).isEqualTo(1);
		assertThat(api.contarConfirmacoesVencidas(corte)).isZero();
		assertThat(api.resumo(velho).orElseThrow().status()).isEqualTo(StatusPedido.CONFIRMACAO_EXPIRADA);
		assertThat(api.resumo(recente).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(api.resumo(confirmado).orElseThrow().status()).isEqualTo(StatusPedido.PAGO);
		assertThat(api.resumo(pago).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO);
		assertThat(api.expirarConfirmacoes(corte)).as("idempotente").isZero();
	}

	/** Review Focus 4: pedido nao confirmado nunca gera oracao, nem pela geracao, nem pelo admin, nem pela varredura. */
	@Test
	void pedidoNaoConfirmadoNuncaGeraOracao() {
		var pendente = freemium("doador-" + UUID.randomUUID() + "@exemplo.com");
		var expirado = freemium("doador-" + UUID.randomUUID() + "@exemplo.com");
		jdbc.update("update pedido set status = 'CONFIRMACAO_EXPIRADA' where id = ?", expirado);
		for (var id : new UUID[] { pendente, expirado }) {
			assertThatThrownBy(() -> api.iniciarGeracao(id)).isInstanceOf(TransicaoInvalidaException.class);
			assertThatThrownBy(() -> api.concederGratuidade(id)).isInstanceOf(TransicaoInvalidaException.class);
			assertThatThrownBy(() -> api.solicitarRegeneracao(id)).isInstanceOf(TransicaoInvalidaException.class);
			assertThatThrownBy(() -> api.falhar(id, "x")).isInstanceOf(TransicaoInvalidaException.class); // sem ERRO, sem alerta
		}
		jdbc.update("update pedido set atualizado_em = now() - interval '30 days'");
		assertThat(api.emAndamentoParados(Duration.ofMinutes(20))).isEmpty();
		var corte = Instant.now().minus(Duration.ofMinutes(20));
		assertThat(api.falharSeParado(pendente, corte, "pedido_preso")).isFalse();
		assertThat(api.falharSeParado(expirado, corte, "pedido_preso")).isFalse();
		assertThat(api.resumo(pendente).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(api.resumo(expirado).orElseThrow().status()).isEqualTo(StatusPedido.CONFIRMACAO_EXPIRADA);
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
	}

	@Test
	void migrationAceitaOsDoisStatusNovosERecusaOutro() {
		var id = freemium("doador-" + UUID.randomUUID() + "@exemplo.com");
		jdbc.update("update pedido set status = 'CONFIRMACAO_EXPIRADA' where id = ?", id);
		jdbc.update("update pedido set status = 'AGUARDANDO_CONFIRMACAO_EMAIL' where id = ?", id);
		assertThatThrownBy(() -> jdbc.update("update pedido set status = 'QUALQUER' where id = ?", id))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'pedido_pendente_freemium_ix'", Integer.class)).isEqualTo(1);
	}

	/** Passa do limite de 32767 parametros do driver: sem fatiar em {@code LOTE}, a consulta inteira estouraria. */
	@Test
	void resumosNomesEDoadoresFatiamListasMaioresQueUmLote() {
		var a = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		var b = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		var c = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		var ids = new java.util.ArrayList<UUID>();
		for (int i = 0; i < 40_000; i++) ids.add(UUID.randomUUID());
		assertThat(ids.size()).isGreaterThan(PedidoApiImpl.LOTE * 7);
		ids.set(0, a);
		ids.set(PedidoApiImpl.LOTE + 1, b); // fora do primeiro lote
		ids.set(ids.size() - 1, c); // no ultimo, parcial

		assertThat(api.resumos(ids)).containsOnlyKeys(a, b, c);
		assertThat(api.nomes(ids)).containsOnlyKeys(a, b, c).containsEntry(b, "Maria Souza");
		assertThat(api.doadores(ids)).containsOnlyKeys(a, b, c);
		assertThat(api.resumos(java.util.List.of())).isEmpty();
	}

	@Test
	void projecoesNaoVazamNomeNemEmailNoToString() {
		assertThat(new PedidoRepository.NomeEEmail(UUID.randomUUID(), "Maria", "maria@exemplo.com").toString()).doesNotContain("Maria", "maria@");
		assertThat(new PedidoRepository.Nome(UUID.randomUUID(), "Maria").toString()).doesNotContain("Maria");
	}
}
