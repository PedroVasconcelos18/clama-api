package me.clama.lgpd;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.UUID;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.Usuario;
import me.clama.conta.web.Navegador;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/** D10 a D13: busca, exportacao, exclusao e "apagar so o blog" do titular. O fluxo de ponta a ponta esta em {@link LgpdFluxoTest}. */
@ExtendWith(OutputCaptureExtension.class)
class LgpdTest extends LgpdBase {

	static final String BUSCA = "/api/admin/lgpd/busca";
	static final String EXPORTACAO = "/api/admin/lgpd/exportacao";
	static final String EXCLUSAO = "/api/admin/lgpd/exclusao";
	static final String DO_BLOG = "/api/admin/lgpd/exclusao-do-blog";

	/** O corpo da exclusao com os ids da previa. */
	static String exclusao(String email, String confirmacao, JsonNode previa) {
		return "{\"email\":\"" + email + "\",\"confirmacao\":\"" + confirmacao + "\",\"pedidoIds\":" + previa.get("pedidoIds")
				+ ",\"contaIds\":" + previa.get("contaIds") + "}";
	}

	JsonNode previa(Navegador nav, String email) throws Exception {
		var res = nav.post(BUSCA, busca(email));
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		return json(res);
	}

	/** Nada do titular mudou: a conta, o pedido com a PII e o numero de pedidos. */
	void nadaMudou(Usuario conta, UUID pedido, int pedidosNoBanco) {
		assertThat(usuarios.findById(conta.getId())).isPresent();
		var p = pedidos.findById(pedido).orElseThrow();
		assertThat(p.getNome()).contains(RASTRO);
		assertThat(p.getEmail()).isEqualTo(conta.getEmail());
		assertThat(p.getOracao() == null || p.getOracao().contains(RASTRO)).isTrue();
		assertThat(contar("select count(*) from pedido")).isEqualTo(pedidosNoBanco);
		assertThat(contar("select count(*) from auditoria where entidade = 'lgpd'")).isZero();
	}

	// --- permissao ---

	@Test
	void anonimoRecebe401EClienteRecebe403EmTodas() throws Exception {
		var cliente = logado(Papel.CLIENTE);
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu"); // emite o cookie do CSRF: sem ele o 403 seria do CSRF, nao do papel
		for (var rota : List.of(BUSCA, EXPORTACAO, EXCLUSAO, DO_BLOG)) {
			assertThat(anonimo.post(rota, busca("a@exemplo.com")).statusCode()).as(rota).isEqualTo(401);
			assertThat(cliente.post(rota, busca("a@exemplo.com")).statusCode()).as(rota).isEqualTo(403);
		}
	}

	@Test
	void adminComumNaoExclui() throws Exception {
		var email = "alvo-" + unico() + "@exemplo.com";
		var alvo = conta(email, Papel.CLIENTE);
		var pedido = pedido(email, null, plano(), null);
		var admin = logado(Papel.ADMIN);
		var res = admin.post(EXCLUSAO, "{\"email\":\"" + email + "\",\"confirmacao\":\"" + email + "\",\"pedidoIds\":[\"" + pedido
				+ "\"],\"contaIds\":[" + alvo.getId() + "]}");
		assertThat(res.statusCode()).isEqualTo(403);
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Só o superusuário exclui os dados de um titular.");
		nadaMudou(alvo, pedido, 1);
	}

	// --- busca ---

	@Test
	void buscaPelaVarianteDoGmailAchaAContaOPedidoAnonimoEOPedidoDoDono() throws Exception {
		var sufixo = unico();
		var conta = conta("joana" + sufixo + "@gmail.com", Papel.CLIENTE);
		var plano = plano();
		var anonimo = pedido("Joana." + sufixo + "+doacao@Gmail.com", null, plano, null); // variante, sem conta
		var doDono = pedido("outro-" + sufixo + "@exemplo.com", conta.getId(), plano, null); // e-mail diferente, mas e dela
		var alheio = pedido("alheio-" + sufixo + "@exemplo.com", null, plano, null);
		paga(doDono, 1001);
		var admin = logado(Papel.ADMIN);

		var r = previa(admin, "j.o.a.n.a" + sufixo + "@googlemail.com");

		assertThat(r.get("contas")).hasSize(1);
		assertThat(r.at("/contas/0/id").asLong()).isEqualTo(conta.getId());
		assertThat(r.at("/contas/0/papel").asString()).isEqualTo("CLIENTE");
		assertThat(r.get("pedidoIds")).extracting(JsonNode::asString).containsExactlyInAnyOrder(anonimo.toString(), doDono.toString());
		assertThat(r.get("contaIds")).extracting(JsonNode::asLong).containsExactly(conta.getId());
		assertThat(r.at("/pedidos/total").asInt()).isEqualTo(2);
		assertThat(r.at("/pedidos/aApagar").asInt()).isEqualTo(1);
		assertThat(r.at("/pedidos/aAnonimizar").asInt()).isEqualTo(1);
		assertThat(r.at("/pedidos/emProcessamento").asInt()).isZero();
		assertThat(r.get("freemiumBloqueado").asBoolean()).isFalse();
		assertThat(alheio).isNotNull();
	}

	@Test
	void buscaSemNadaDevolveZeros() throws Exception {
		var r = previa(logado(Papel.ADMIN), "ninguem-" + unico() + "@exemplo.com");
		assertThat(r.get("contas")).isEmpty();
		assertThat(r.at("/pedidos/total").asInt()).isZero();
		assertThat(r.get("pedidoIds")).isEmpty();
	}

	@Test
	void previaContaProcessamentoBlogEBloqueio() throws Exception {
		var email = "t-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var adminConta = conta("adm-" + unico() + "@exemplo.com", Papel.ADMIN);
		var admin = entrar(adminConta.getEmail());
		var processando = pedido(email, null, plano(), null);
		status(processando, "GERANDO_ORACAO");
		blog(conta.getId(), 7);
		banimentos.banir(adminConta.getId(), conta.getId(), "motivo do ban");
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, criado_em) values (?, ?, now())", hmac.email(email), hmac.cpf("52998224725"));

		var r = previa(admin, email);

		assertThat(r.at("/pedidos/emProcessamento").asInt()).isEqualTo(1);
		assertThat(r.at("/blog/comentarios").asLong()).isEqualTo(1);
		assertThat(r.at("/blog/curtidas").asLong()).isEqualTo(1);
		assertThat(r.at("/blog/banido").asBoolean()).isTrue();
		assertThat(r.get("freemiumBloqueado").asBoolean()).isTrue();
	}

	@Test
	void bloqueioPeloCpfDoPedidoTambemConta() throws Exception {
		var email = "t-" + unico() + "@exemplo.com";
		pedido(email, null, plano(), null);
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, criado_em) values (?, ?, now())", "outro-hmac", hmac.cpf("52998224725"));
		assertThat(previa(logado(Papel.ADMIN), email).get("freemiumBloqueado").asBoolean()).isTrue();
	}

	// --- exportacao ---

	@Test
	void exportaTudoDecifradoSemSenhaEAuditaSoContagens(CapturedOutput out) throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var adminConta = conta("adm-" + unico() + "@exemplo.com", Papel.ADMIN);
		var instituicao = instituicao("Lar Esperança");
		var pedido = pedido(email, conta.getId(), plano(), instituicao);
		enviado(pedido);
		paga(pedido, 5005);
		jdbc.update("insert into freemium_confirmacao (pedido_id, hash, criado_em, expira_em, usado_em) values (?, ?, now(), now() + interval '1 day', now())",
				pedido, "a".repeat(64));
		blog(conta.getId(), 9);
		banimentos.banir(adminConta.getId(), conta.getId(), "spam repetido");
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, criado_em) values (?, ?, now())", hmac.email(email), hmac.cpf("52998224725"));
		var hash = jdbc.queryForObject("select senha from usuario where id = ?", String.class, conta.getId());

		var res = entrar(adminConta.getEmail()).post(EXPORTACAO, busca(email));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
		assertThat(res.headers().firstValue("Content-Disposition").orElse("")).matches("attachment; filename=\"lgpd_\\d{8}_\\d{6}\\.json\"");
		var j = json(res);
		assertThat(j.get("email").asString()).isEqualTo(email);
		assertThat(j.get("geradoEm").asString()).endsWith("Z");
		assertThat(j.at("/contas/0/email").asString()).isEqualTo(email);
		assertThat(j.at("/contas/0/papel").asString()).isEqualTo("CLIENTE");
		assertThat(j.at("/contas/0/nomeCompleto").asString()).isEqualTo("Fulana de Tal");
		assertThat(j.at("/contas/0").propertyNames()).containsExactlyInAnyOrder("id", "email", "nomeCompleto", "nomeFormatoBlog", "papel", "ativo",
				"criadoEm", "ultimoLoginEm");
		assertThat(res.body()).doesNotContain(hash).doesNotContainIgnoringCase("senha");

		var p = j.at("/pedidos/0");
		assertThat(j.get("pedidos")).hasSize(1);
		assertThat(p.get("id").asString()).isEqualTo(pedido.toString());
		assertThat(p.get("status").asString()).isEqualTo("ENVIADA");
		assertThat(p.get("instituicao").asString()).isEqualTo("Lar Esperança");
		assertThat(p.get("valorCentavos").asLong()).isEqualTo(3000);
		assertThat(p.get("nome").asString()).contains(RASTRO);
		assertThat(p.get("email").asString()).isEqualTo(email);
		assertThat(p.get("cpfCnpj").asString()).isEqualTo("52998224725");
		assertThat(p.get("telefone").asString()).isEqualTo("11987654321");
		assertThat(p.get("idade").asInt()).isEqualTo(41);
		assertThat(p.get("pedidoOracao").asString()).contains(RASTRO);
		assertThat(p.get("oracao").asString()).contains(RASTRO);
		assertThat(p.at("/consentimento/ip").asString()).isEqualTo(IP_CONSENTIMENTO);
		assertThat(p.at("/consentimento/versao").asString()).isEqualTo("1");
		assertThat(p.at("/consentimento/em").asString()).endsWith("Z");
		assertThat(p.at("/pagamento/idNoMercadoPago").asLong()).isEqualTo(5005);
		assertThat(p.at("/pagamento/confirmadoEm").asString()).endsWith("Z");
		assertThat(p.get("confirmacoesFreemium")).hasSize(1);
		assertThat(p.at("/confirmacoesFreemium/0").propertyNames()).containsExactlyInAnyOrder("criadoEm", "expiraEm", "usadoEm");

		assertThat(j.at("/blog/comentarios/0/ip").asString()).isEqualTo(IP_COMENTARIO);
		assertThat(j.at("/blog/comentarios/0/postSlug").asString()).isEqualTo("post-9");
		assertThat(j.at("/blog/comentarios/0/conteudo").asString()).contains(RASTRO);
		assertThat(j.at("/blog/curtidas/0/postSlug").asString()).isEqualTo("post-9");
		assertThat(j.at("/blog/banimento/motivo").asString()).isEqualTo("spam repetido");
		assertThat(j.at("/blog/historicoDeBanimentos/0/acao").asString()).isEqualTo("ADICAO");
		assertThat(j.at("/blog/historicoDeBanimentos/0/motivo").asString()).isEqualTo("spam repetido");
		assertThat(j.at("/blog/historicoDeBanimentos/0/em").asString()).endsWith("Z");
		assertThat(j.at("/freemium").propertyNames()).containsExactly("bloqueado");
		assertThat(j.at("/freemium/bloqueado").asBoolean()).isTrue();
		assertThat(j.get("compartilhamento")).extracting(n -> n.get("destinatario").asString()).containsExactly("Mercado Pago", "Anthropic", "Resend");

		var diff = jdbc.queryForObject("select diff::text from auditoria where entidade = 'lgpd_exportacao' and acao = 'ADICAO'", String.class);
		assertThat(diff).contains("\"pedidos\": 1", "\"comentarios\": 1").doesNotContain("@").doesNotContain(email);
		assertThat(out.getAll()).doesNotContain(email).doesNotContain(RASTRO).doesNotContain(IP_COMENTARIO);
	}

	@Test
	void exportaOPedidoAnonimoSemContaComSecoesVazias() throws Exception {
		var email = "so-pedido-" + unico() + "@exemplo.com";
		var pedido = pedido(email, null, plano(), null);

		var j = json(logado(Papel.ADMIN).post(EXPORTACAO, busca(email)));

		assertThat(j.get("contas")).isEmpty();
		assertThat(j.get("pedidos")).hasSize(1);
		assertThat(j.at("/pedidos/0/id").asString()).isEqualTo(pedido.toString());
		assertThat(j.at("/pedidos/0/pagamento").isNull()).isTrue();
		assertThat(j.at("/pedidos/0/confirmacoesFreemium")).isEmpty();
		assertThat(j.at("/blog/comentarios")).isEmpty();
		assertThat(j.at("/blog/banimento").isNull()).isTrue();
		assertThat(j.at("/freemium/bloqueado").asBoolean()).isFalse();
	}

	@Test
	void exportarSemDadosEh404SemAuditar() throws Exception {
		var res = logado(Papel.ADMIN).post(EXPORTACAO, busca("ninguem-" + unico() + "@exemplo.com"));
		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(json(res).get("codigo").asString()).isEqualTo("titular_nao_encontrado");
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Não encontramos dados para esse e-mail.");
		assertThat(contar("select count(*) from auditoria where entidade = 'lgpd_exportacao'")).isZero();
	}

	@Test
	void emailGrandeDemaisEhRecusado() throws Exception {
		assertThat(logado(Papel.ADMIN).post(BUSCA, busca("a".repeat(300) + "@exemplo.com")).statusCode()).isEqualTo(400);
	}

	// --- exclusao ---

	@Test
	void excluiAnonimizaApagaEExcluiAContaSemEmailNoLog(CapturedOutput out) throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var sessao = entrar(email);
		var adminConta = conta("adm-" + unico() + "@exemplo.com", Papel.ADMIN);
		var instituicao = instituicao("Lar Esperança");
		var plano = plano();
		var pago = pedido(email, conta.getId(), plano, instituicao);
		enviado(pago);
		paga(pago, 2001);
		var comRepasse = pedido(email, null, plano, instituicao);
		enviado(comRepasse);
		repasse(comRepasse, instituicao);
		var semPagamento = pedido(email, null, plano, instituicao);
		cobranca(semPagamento, 2003);
		var freemium = pedido(email, null, plano, null);
		status(freemium, "AGUARDANDO_CONFIRMACAO_EMAIL");
		jdbc.update("insert into freemium_confirmacao (pedido_id, hash, criado_em, expira_em) values (?, ?, now(), now() + interval '1 day')", freemium, "b".repeat(64));
		var alheio = pedido("alheio-" + unico() + "@exemplo.com", null, plano, instituicao);
		blog(conta.getId(), 11);
		banimentos.banir(adminConta.getId(), conta.getId(), "motivo livre " + RASTRO);
		jdbc.update("insert into token_de_senha (usuario_id, hash, finalidade, criado_em, expira_em) values (?, ?, 'REDEFINIR', now(), now() + interval '1 day')",
				conta.getId(), "c".repeat(64));
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, criado_em) values (?, ?, now())", hmac.email(email), hmac.cpf("52998224725"));
		var super_ = logado(Papel.SUPERUSUARIO);

		var previa = previa(super_, email);
		var res = super_.post(EXCLUSAO, exclusao(email, email, previa));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(json(res).propertyNames()).containsExactlyInAnyOrder("pedidosApagados", "pedidosAnonimizados", "contasExcluidas");
		assertThat(json(res).get("pedidosApagados").asInt()).isEqualTo(2);
		assertThat(json(res).get("pedidosAnonimizados").asInt()).isEqualTo(2);
		assertThat(json(res).get("contasExcluidas").asInt()).isEqualTo(1);

		for (var id : List.of(pago, comRepasse)) {
			var p = pedidos.findById(id).orElseThrow();
			assertThat(p.getNome()).isEmpty();
			assertThat(p.getEmail()).isEmpty();
			assertThat(p.getEmailHmac()).isEmpty();
			assertThat(p.getCpfCnpj()).isNull();
			assertThat(p.getCpfHmac()).isNull();
			assertThat(p.getTelefone()).isNull();
			assertThat(p.getIdade()).isNull();
			assertThat(p.getSexo()).isNull();
			assertThat(p.getPedidoOracao()).isNull();
			assertThat(p.getOracao()).isNull();
			assertThat(p.getConsentimentoIp()).isNull();
			assertThat(p.getUsuarioId()).isNull();
			assertThat(p.getStatus()).isEqualTo(StatusPedido.ENVIADA);
			assertThat(p.getValorCentavos()).isEqualTo(3000);
			assertThat(p.getPlanoId()).isEqualTo(plano);
			assertThat(p.getInstituicaoId()).isEqualTo(instituicao);
			assertThat(p.getCriadoEm()).isNotNull();
			assertThat(p.getConsentidoEm()).isNotNull();
		}
		assertThat(contar("select count(*) from cobranca where pedido_id = ? and paga_em is not null", pago)).isEqualTo(1);
		assertThat(contar("select count(*) from repasse where pedido_id = ?", comRepasse)).isEqualTo(1);
		assertThat(pedidos.findById(semPagamento)).isEmpty();
		assertThat(pedidos.findById(freemium)).isEmpty();
		assertThat(contar("select count(*) from cobranca where pedido_id = ?", semPagamento)).isZero();
		assertThat(contar("select count(*) from freemium_confirmacao where pedido_id = ?", freemium)).isZero();
		assertThat(pedidos.findById(alheio)).isPresent();

		assertThat(usuarios.findById(conta.getId())).isEmpty();
		assertThat(sessao.get("/api/auth/eu").statusCode()).isEqualTo(401);
		assertThat(contar("select count(*) from blog_comentario")).isZero();
		assertThat(contar("select count(*) from blog_curtida")).isZero();
		assertThat(contar("select count(*) from blog_banimento")).isZero();
		assertThat(contar("select count(*) from token_de_senha where usuario_id = ?", conta.getId())).isZero();
		assertThat(contar("select count(*) from freemium_bloqueio")).isEqualTo(1);

		var motivos = jdbc.queryForList("select diff->>'motivo' from auditoria where entidade = 'banimento' and entidade_id = ?", String.class,
				String.valueOf(conta.getId()));
		assertThat(motivos).isNotEmpty().containsOnly("[removido na exclusão LGPD]");
		var diff = jdbc.queryForObject("select diff::text from auditoria where entidade = 'lgpd' and acao = 'EXCLUSAO'", String.class);
		assertThat(diff).contains("\"pedidosApagados\": 2", "\"pedidosAnonimizados\": 2", "\"contasExcluidas\": 1").doesNotContain("@");
		assertThat(contar("select count(*) from auditoria where entidade = 'usuario' and entidade_id = ? and acao = 'EXCLUSAO'", String.valueOf(conta.getId()))).isEqualTo(1);
		assertThat(contar("select count(*) from auditoria where diff::text like ?", "%" + RASTRO + "%")).isZero();
		assertThat(out.getAll()).doesNotContain(email).doesNotContain(RASTRO);
		assertThat(previa(super_, email).get("pedidoIds")).isEmpty();
	}

	/**
	 * Carry da T2 (M1): uma falha no ULTIMO passo (a auditoria da exclusao, depois de anonimizar, apagar, redigir o motivo
	 * do ban e excluir a conta com as sessoes) desfaz tudo; a unica excecao e a sessao do titular (ver o achado abaixo).
	 */
	@Test
	void falhaNoUltimoPassoDesfazTudoMenosASessao() throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var sessao = entrar(email);
		var adminConta = conta("adm-" + unico() + "@exemplo.com", Papel.ADMIN);
		var plano = plano();
		var pago = pedido(email, conta.getId(), plano, null);
		enviado(pago);
		paga(pago, 4001);
		var semPagamento = pedido(email, conta.getId(), plano, null);
		blog(conta.getId(), 31);
		banimentos.banir(adminConta.getId(), conta.getId(), "motivo livre " + RASTRO);
		var super_ = logado(Papel.SUPERUSUARIO);
		var previa = previa(super_, email);
		int sessoesAntes = contar("select count(*) from spring_session where principal_name = ?", conta.getId().toString());
		assertThat(sessoesAntes).isEqualTo(1);
		// o proxy transacional da Auditoria (MANDATORY) exige transacao ate para programar o espiao
		transacao.executeWithoutResult(t -> doThrow(new IllegalStateException("falha forcada")).when(auditoria).registrar(any(), eq("lgpd"), any(), any(), any()));

		var res = super_.post(EXCLUSAO, exclusao(email, email, previa));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(500);
		var p = pedidos.findById(pago).orElseThrow();
		assertThat(p.getNome()).contains(RASTRO);
		assertThat(p.getEmail()).isEqualTo(email);
		assertThat(p.getEmailHmac()).isNotEmpty();
		assertThat(p.getOracao()).contains(RASTRO);
		assertThat(p.getUsuarioId()).isEqualTo(conta.getId());
		assertThat(pedidos.findById(semPagamento)).isPresent();
		assertThat(usuarios.findById(conta.getId())).isPresent();
		assertThat(contar("select count(*) from blog_comentario")).isEqualTo(1);
		assertThat(contar("select count(*) from blog_banimento where usuario_id = ?", conta.getId())).isEqualTo(1);
		var motivos = jdbc.queryForList("select diff->>'motivo' from auditoria where entidade = 'banimento' and entidade_id = ?", String.class,
				String.valueOf(conta.getId()));
		assertThat(motivos).isNotEmpty().noneMatch(m -> m.equals("[removido na exclusão LGPD]")).anyMatch(m -> m.contains(RASTRO));
		assertThat(contar("select count(*) from auditoria where entidade = 'usuario' and entidade_id = ? and acao = 'EXCLUSAO'", String.valueOf(conta.getId()))).isZero();
		assertThat(contar("select count(*) from auditoria where entidade = 'lgpd'")).isZero();
		// ACHADO (T3): a sessao NAO volta. O Spring Session JDBC apaga em transacao propria (REQUIRES_NEW) e ela ja commitou;
		// o titular so precisa entrar de novo, e nenhum dado fica errado. Ver task-3-report.md.
		assertThat(contar("select count(*) from spring_session where principal_name = ?", conta.getId().toString())).as("sessao do titular").isZero();
		assertThat(sessao.get("/api/auth/eu").statusCode()).isEqualTo(401);
	}

	@Test
	void pedidoEmProcessamentoEh409ENadaMuda() throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var plano = plano();
		var processando = pedido(email, conta.getId(), plano, null);
		var super_ = logado(Papel.SUPERUSUARIO);
		for (var status : List.of("PAGO", "GERANDO_ORACAO", "ORACAO_GERADA", "AGUARDANDO_NOVA_TENTATIVA")) {
			status(processando, status);
			var previa = previa(super_, email);
			assertThat(previa.at("/pedidos/emProcessamento").asInt()).as(status).isEqualTo(1);
			var res = super_.post(EXCLUSAO, exclusao(email, email, previa));
			assertThat(res.statusCode()).as(status).isEqualTo(409);
			assertThat(json(res).get("codigo").asString()).isEqualTo("pedido_em_processamento");
			assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Há pedido sendo processado agora. Tente de novo em alguns minutos.");
		}
		nadaMudou(conta, processando, 1);
	}

	@Test
	void jobPendenteNaFilaEh409ENadaMuda() throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var pedido = pedido(email, conta.getId(), plano(), null);
		enviado(pedido);
		jobPendente("alerta-admin", "alerta-admin/" + pedido + "/falha_envio");
		var super_ = logado(Papel.SUPERUSUARIO);

		var previa = previa(super_, email);
		var res = super_.post(EXCLUSAO, exclusao(email, email, previa));

		assertThat(previa.at("/pedidos/emProcessamento").asInt()).isEqualTo(1);
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(json(res).get("codigo").asString()).isEqualTo("pedido_em_processamento");
		nadaMudou(conta, pedido, 1);
	}

	@Test
	void conjuntoQueMudouDesdeABuscaEh409ENadaMuda() throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var plano = plano();
		var primeiro = pedido(email, conta.getId(), plano, null);
		var super_ = logado(Papel.SUPERUSUARIO);
		var previa = previa(super_, email);
		pedido(email, null, plano, null); // chegou depois da busca

		var res = super_.post(EXCLUSAO, exclusao(email, email, previa));

		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(json(res).get("codigo").asString()).isEqualTo("titular_mudou");
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Os dados desse e-mail mudaram desde a busca. Busque de novo antes de excluir.");
		nadaMudou(conta, primeiro, 2);
		// ids que nao sao do titular (ou a menos) tambem recusam
		var estranho = super_.post(EXCLUSAO, "{\"email\":\"" + email + "\",\"confirmacao\":\"" + email + "\",\"pedidoIds\":[\"" + UUID.randomUUID()
				+ "\"],\"contaIds\":[" + conta.getId() + "]}");
		assertThat(estranho.statusCode()).isEqualTo(409);
		nadaMudou(conta, primeiro, 2);
	}

	@Test
	void ultimoSuperusuarioEh409ENadaMuda() throws Exception {
		var eu = conta("eu-" + unico() + "@exemplo.com", Papel.SUPERUSUARIO);
		var nav = entrar(eu.getEmail());
		var email = "unico-" + unico() + "@exemplo.com";
		var unico = conta(email, Papel.SUPERUSUARIO);
		var pedido = pedido(email, unico.getId(), plano(), null);
		var previa = previa(nav, email);
		eu.promover(Papel.ADMIN); // a sessao ainda diz SUPERUSUARIO: o alvo e o ultimo de verdade
		usuarios.save(eu);

		var res = nav.post(EXCLUSAO, exclusao(email, email, previa));

		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(json(res).get("codigo").asString()).isEqualTo("ultimo_superusuario");
		nadaMudou(unico, pedido, 1);
		assertThat(contar("select count(*) from auditoria where entidade = 'usuario' and entidade_id = ? and acao = 'EXCLUSAO'", String.valueOf(unico.getId()))).isZero();
	}

	@Test
	void naoExcluiASiMesmo() throws Exception {
		var email = "eu-" + unico() + "@exemplo.com";
		var eu = conta(email, Papel.SUPERUSUARIO);
		conta("outro-" + unico() + "@exemplo.com", Papel.SUPERUSUARIO);
		var pedido = pedido(email, eu.getId(), plano(), null);
		var nav = entrar(email);
		var res = nav.post(EXCLUSAO, exclusao(email, email, previa(nav, email)));
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(json(res).get("codigo").asString()).isEqualTo("nao_pode_excluir_a_si_mesmo");
		nadaMudou(eu, pedido, 1);
	}

	@Test
	void confirmacaoErradaEh400ENadaMuda() throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var pedido = pedido(email, conta.getId(), plano(), null);
		var super_ = logado(Papel.SUPERUSUARIO);
		var previa = previa(super_, email);

		var res = super_.post(EXCLUSAO, exclusao(email, "outro@exemplo.com", previa));

		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Digite o e-mail exatamente como aparece para confirmar.");
		assertThat(json(res).at("/erros/0/campo").asString()).isEqualTo("confirmacao");
		nadaMudou(conta, pedido, 1);
	}

	@Test
	void excluirEmailSemDadosEh404() throws Exception {
		var email = "ninguem-" + unico() + "@exemplo.com";
		var res = logado(Papel.SUPERUSUARIO).post(EXCLUSAO, "{\"email\":\"" + email + "\",\"confirmacao\":\"" + email + "\",\"pedidoIds\":[],\"contaIds\":[]}");
		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(json(res).get("codigo").asString()).isEqualTo("titular_nao_encontrado");
	}

	@Test
	void listasDeIdsSemLimiteSaoRecusadas() throws Exception {
		var email = "a-" + unico() + "@exemplo.com";
		var ids = "\"" + UUID.randomUUID() + "\",";
		var corpo = "{\"email\":\"" + email + "\",\"confirmacao\":\"" + email + "\",\"pedidoIds\":[" + ids.repeat(10001) + "\"" + UUID.randomUUID() + "\"],\"contaIds\":[]}";
		assertThat(logado(Papel.SUPERUSUARIO).post(EXCLUSAO, corpo).statusCode()).isEqualTo(400);
	}

	// --- apagar so o blog ---

	@Test
	void apagarSoOBlogMantemContaPedidosEBan() throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		var conta = conta(email, Papel.CLIENTE);
		var adminConta = conta("adm-" + unico() + "@exemplo.com", Papel.ADMIN);
		var pedido = pedido(email, conta.getId(), plano(), null);
		paga(pedido, 3001);
		blog(conta.getId(), 21);
		banimentos.banir(adminConta.getId(), conta.getId(), "motivo do ban");
		var admin = entrar(adminConta.getEmail());

		var res = admin.post(DO_BLOG, "{\"email\":\"" + email + "\",\"confirmacao\":\"" + email + "\"}");

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(json(res).get("comentarios").asLong()).isEqualTo(1);
		assertThat(json(res).get("curtidas").asLong()).isEqualTo(1);
		assertThat(contar("select count(*) from blog_comentario")).isZero();
		assertThat(contar("select count(*) from blog_curtida")).isZero();
		assertThat(contar("select count(*) from blog_banimento where usuario_id = ?", conta.getId())).isEqualTo(1);
		assertThat(usuarios.findById(conta.getId())).isPresent();
		var p = pedidos.findById(pedido).orElseThrow();
		assertThat(p.getNome()).contains(RASTRO);
		assertThat(p.getUsuarioId()).isEqualTo(conta.getId());
		assertThat(contar("select count(*) from cobranca where pedido_id = ?", pedido)).isEqualTo(1);
		var diff = jdbc.queryForObject("select diff::text from auditoria where entidade = 'lgpd_blog' and acao = 'EXCLUSAO'", String.class);
		assertThat(diff).contains("\"comentarios\": 1", "\"curtidas\": 1").doesNotContain("@");
		assertThat(jdbc.queryForObject("select diff->>'motivo' from auditoria where entidade = 'banimento' and entidade_id = ?", String.class,
				String.valueOf(conta.getId()))).isEqualTo("motivo do ban"); // o ban e o historico ficam como estavam
	}

	@Test
	void apagarSoOBlogExigeConfirmacaoEContaExistente() throws Exception {
		var email = "titular-" + unico() + "@exemplo.com";
		conta(email, Papel.CLIENTE);
		var admin = logado(Papel.ADMIN);
		var errada = admin.post(DO_BLOG, "{\"email\":\"" + email + "\",\"confirmacao\":\"x@exemplo.com\"}");
		assertThat(errada.statusCode()).isEqualTo(400);
		var sem = "ninguem-" + unico() + "@exemplo.com";
		assertThat(admin.post(DO_BLOG, "{\"email\":\"" + sem + "\",\"confirmacao\":\"" + sem + "\"}").statusCode()).isEqualTo(404);
		assertThat(contar("select count(*) from auditoria where entidade = 'lgpd_blog'")).isZero();
	}
}
