package me.clama.lgpd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.EmailDeTeste;
import me.clama.blog.interno.Banimentos;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.Usuario;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.cripto.Cifra;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Base dos testes da LGPD: banco limpo antes e depois, contas logadas e fixtures de pedido, cobranca, repasse e blog. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
abstract class LgpdBase {

	static final String SENHA = "senha-forte-123";
	static final String IP_CONSENTIMENTO = "203.0.113.9";
	static final String IP_COMENTARIO = "198.51.100.77";
	static final String RASTRO = "ZZ-RASTRO";

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired PedidoRepository pedidos;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired HmacDeIdentificador hmac;
	@Autowired Cifra cifra;
	@Autowired LimiteDeTaxa limite;
	@Autowired Banimentos banimentos;
	@Autowired org.springframework.transaction.support.TransactionTemplate transacao;
	/** Espiao (so age quando um teste o programa): permite forcar uma falha no meio da exclusao. */
	@MockitoSpyBean Auditoria auditoria;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		jdbc.update("delete from auditoria where entidade in ('lgpd', 'lgpd_exportacao', 'lgpd_blog', 'banimento')");
		jdbc.update("delete from blog_comentario");
		jdbc.update("delete from blog_curtida");
		jdbc.update("delete from blog_banimento");
		jdbc.update("delete from blog_post");
		jdbc.update("delete from freemium_confirmacao");
		jdbc.update("delete from freemium_bloqueio");
		jdbc.update("delete from repasse");
		jdbc.update("delete from cobranca");
		jdbc.update("delete from pagamento_evento where id_externo like 'ev-%'");
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where email like '%@exemplo.com' or email like '%@gmail.com'");
		jdbc.update("delete from usuario where papel = 'SUPERUSUARIO'"); // as guardas contam o banco todo
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from instituicao");
	}

	static String unico() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	Usuario conta(String email, Papel papel) {
		return Navegador.usuario(usuarios, encoder, email, SENHA, papel);
	}

	Navegador entrar(String email) throws Exception {
		var nav = new Navegador(porta);
		assertThat(nav.login(email, SENHA).statusCode()).isEqualTo(200);
		return nav;
	}

	/** Conta nova com o papel, ja logada. */
	Navegador logado(Papel papel) throws Exception {
		var email = "adm-" + unico() + "@exemplo.com";
		conta(email, papel);
		return entrar(email);
	}

	Long instituicao(String nome) {
		return jdbc.queryForObject("insert into instituicao (nome) values (?) returning id", Long.class, nome);
	}

	Long plano() {
		return jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 3000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Plano " + unico());
	}

	/** Pedido AGUARDANDO_PAGAMENTO com PII conhecida; {@code usuarioId} nulo = doacao anonima. */
	UUID pedido(String email, Long usuarioId, Long plano, Long instituicao) {
		var agora = Instant.now();
		var d = new Pedido.Dados(usuarioId, plano, instituicao, 3000, "Maria Souza " + RASTRO, email, "52998224725", "11987654321",
				(short) 41, "F", "Pela minha mae Joana " + RASTRO, agora, IP_CONSENTIMENTO, "1");
		return pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
	}

	/** Do pedido aguardando ate ENVIADA, com a oracao gerada. */
	void enviado(UUID id) {
		var p = pedidos.findById(id).orElseThrow();
		var agora = Instant.now();
		p.confirmarPagamento(agora);
		p.iniciarGeracao(agora);
		p.registrarOracao("Senhor, cuida da Joana. " + RASTRO, agora);
		p.marcarEnviado(agora);
		pedidos.saveAndFlush(p);
	}

	void status(UUID id, String status) {
		jdbc.update("update pedido set status = ? where id = ?", status, id);
	}

	/** Cobranca vigente, nao paga. */
	void cobranca(UUID pedido, long mpId) {
		jdbc.update("""
				insert into cobranca (pedido_id, n, mp_pagamento_id, pix_copia_e_cola, expira_em, criada_em)
				values (?, 1, ?, 'qr', now() + interval '1 day', now())""", pedido, mpId);
	}

	/** A cobranca que pagou o pedido. */
	void paga(UUID pedido, long mpId) {
		cobranca(pedido, mpId);
		jdbc.update("update cobranca set paga_em = now() where pedido_id = ?", pedido);
	}

	void repasse(UUID pedido, Long instituicao) {
		jdbc.update("""
				insert into repasse (pedido_id, instituicao_id, valor_base_centavos, percentual, valor_centavos, status, criado_em)
				values (?, ?, 3000, 20, 600, 'REGISTRADO', now())""", pedido, instituicao);
	}

	void jobPendente(String tarefa, String instancia) {
		jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, version) values (?, ?, now(), false, 1)",
				tarefa, instancia);
	}

	/** Post espelhado, um comentario com IP cifrado e uma curtida da conta. */
	void blog(Long usuarioId, long wpId) {
		jdbc.update("insert into blog_post (wp_id, slug, publicado, atualizado_em) values (?, ?, true, now())", wpId, "post-" + wpId);
		jdbc.update("insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, ip_cifrado, criado_em) values (?, ?, ?, false, ?, now())",
				wpId, usuarioId, "Amém, que Deus abençoe " + RASTRO, cifra.cifrar(IP_COMENTARIO));
		jdbc.update("insert into blog_curtida (post_id, usuario_id, criado_em) values (?, ?, now())", wpId, usuarioId);
	}

	int contar(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	static String busca(String email) {
		return "{\"email\":\"" + email + "\"}";
	}

	static JsonNode json(java.net.http.HttpResponse<String> r) {
		return Navegador.json(r);
	}
}
