package me.clama.blog.web.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import me.clama.conta.interno.Papel;
import org.junit.jupiter.api.Test;

/** Bans do admin (D9): 201/200, validacoes, revogar, efeito no widget, concorrencia, auditoria. */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class BanimentosAdminTest extends WidgetTeste {

	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean me.clama.conta.ContaApi conta;

	/** Carregado da revisao da T3: a conta some entre a checagem e o insert; era FK violada (500), agora 404 pastoral. */
	@Test
	void contaApagadaEntreAChecagemEOInsertE404() throws Exception {
		var admin = logado("Admin Um", Papel.ADMIN);
		var alvo = logado();
		org.mockito.Mockito.doAnswer(inv -> {
			var resumos = inv.callRealMethod();
			// outra thread = outra conexao: a leitura do ContaApi corre numa transacao somente-leitura
			java.util.concurrent.CompletableFuture.runAsync(() -> jdbc.update("delete from usuario where id = ?", alvo.id())).join();
			return resumos;
		}).when(conta).resumos(org.mockito.ArgumentMatchers.anyCollection());
		try {
			var res = admin.nav().post("/api/admin/blog/banimentos", corpo(alvo.id(), "motivo"));
			assertThat(res.statusCode()).isEqualTo(404);
			assertThat(res.body()).contains("Esse usuário não foi encontrado.");
			assertThat(quantos("blog_banimento")).isZero();
			assertThat(auditorias(alvo.id(), "ADICAO")).isZero();
		} finally {
			org.mockito.Mockito.reset(conta);
		}
	}

	private static String corpo(Long id, String motivo) {
		return "{\"usuarioId\":" + id + ",\"motivo\":\"" + motivo + "\"}";
	}

	private long auditorias(Long id, String acao) {
		return jdbc.queryForObject("select count(*) from auditoria where entidade = 'banimento' and entidade_id = ? and acao = ?", Long.class,
				String.valueOf(id), acao);
	}

	@Test
	void banir201ComAuditoriaEDeNovo200SemMudarOMotivo() throws Exception {
		var admin = logado("Admin Um", Papel.ADMIN);
		var alvo = logado("Ana Maria de Souza", Papel.CLIENTE);
		var res = admin.nav().post("/api/admin/blog/banimentos", corpo(alvo.id(), "  spam repetido "));
		assertThat(res.statusCode()).isEqualTo(201);
		var j = json(res);
		assertThat(j.get("usuarioId").asLong()).isEqualTo(alvo.id());
		assertThat(j.get("usuarioEmail").asString()).isEqualTo(alvo.email());
		assertThat(j.get("usuarioNome").asString()).isEqualTo("Ana S.");
		assertThat(j.get("motivo").asString()).isEqualTo("spam repetido");
		assertThat(j.get("banidoPorEmail").asString()).isEqualTo(admin.email());
		assertThat(j.get("banidoEm").asString()).isNotBlank();
		var de_novo = admin.nav().post("/api/admin/blog/banimentos", corpo(alvo.id(), "outro motivo"));
		assertThat(de_novo.statusCode()).isEqualTo(200);
		assertThat(json(de_novo).get("motivo").asString()).isEqualTo("spam repetido");
		assertThat(quantos("blog_banimento")).isEqualTo(1);
		assertThat(auditorias(alvo.id(), "ADICAO")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select diff::text from auditoria where entidade = 'banimento' and acao = 'ADICAO' and entidade_id = ?", String.class, String.valueOf(alvo.id())))
				.contains("spam repetido");
	}

	@Test
	void doisPostSimultaneosDeixamUmBan() throws Exception {
		var admin = logado("Admin Um", Papel.ADMIN);
		var alvo = logado();
		var largada = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var futuros = new ArrayList<java.util.concurrent.Future<Integer>>();
			for (int i = 0; i < 2; i++) {
				futuros.add(pool.submit(() -> {
					largada.await();
					return admin.nav().post("/api/admin/blog/banimentos", corpo(alvo.id(), "concorrente")).statusCode();
				}));
			}
			largada.countDown();
			var codigos = new ArrayList<Integer>();
			for (var f : futuros) codigos.add(f.get());
			assertThat(codigos).containsExactlyInAnyOrder(200, 201);
		} finally {
			pool.shutdownNow();
		}
		assertThat(quantos("blog_banimento")).isEqualTo(1);
		assertThat(auditorias(alvo.id(), "ADICAO")).isEqualTo(1);
	}

	@Test
	void validacoes() throws Exception {
		var admin = logado("Admin Um", Papel.ADMIN);
		var cliente = logado();
		var outroAdmin = logado("Admin Dois", Papel.ADMIN);
		var superu = logado("Super", Papel.SUPERUSUARIO);
		var curto = admin.nav().post("/api/admin/blog/banimentos", corpo(cliente.id(), "  ab "));
		assertThat(curto.statusCode()).isEqualTo(400);
		assertThat(json(curto).get("mensagemPastoral").asString()).isEqualTo("Informe um motivo para o banimento (≥3 caracteres).");
		assertThat(admin.nav().post("/api/admin/blog/banimentos", corpo(cliente.id(), "x".repeat(501))).statusCode()).isEqualTo(400);
		assertThat(admin.nav().post("/api/admin/blog/banimentos", "{\"usuarioId\":" + cliente.id() + "}").statusCode()).isEqualTo(400);
		var inexistente = admin.nav().post("/api/admin/blog/banimentos", corpo(-5L, "motivo"));
		assertThat(inexistente.statusCode()).isEqualTo(404);
		assertThat(json(inexistente).get("mensagemPastoral").asString()).isEqualTo("Esse usuário não foi encontrado.");
		for (var alvo : new Logado[] { outroAdmin, superu }) {
			var res = admin.nav().post("/api/admin/blog/banimentos", corpo(alvo.id(), "motivo"));
			assertThat(res.statusCode()).isEqualTo(409);
			assertThat(json(res).get("codigo").asString()).isEqualTo("nao_pode_banir_admin");
		}
		assertThat(quantos("blog_banimento")).isZero();
		assertThat(auditorias(cliente.id(), "ADICAO") + auditorias(outroAdmin.id(), "ADICAO") + auditorias(superu.id(), "ADICAO")).isZero();
	}

	@Test
	void revogar204ComAuditoriaDoMotivoESemBan404() throws Exception {
		var admin = logado("Admin Um", Papel.ADMIN);
		var alvo = logado();
		admin.nav().post("/api/admin/blog/banimentos", corpo(alvo.id(), "motivo guardado"));
		assertThat(admin.nav().delete("/api/admin/blog/banimentos/" + alvo.id()).statusCode()).isEqualTo(204);
		assertThat(quantos("blog_banimento")).isZero();
		assertThat(auditorias(alvo.id(), "EXCLUSAO")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select diff::text from auditoria where entidade = 'banimento' and acao = 'EXCLUSAO' and entidade_id = ?", String.class, String.valueOf(alvo.id())))
				.contains("motivo guardado");
		var de_novo = admin.nav().delete("/api/admin/blog/banimentos/" + alvo.id());
		assertThat(de_novo.statusCode()).isEqualTo(404);
		assertThat(json(de_novo).get("mensagemPastoral").asString()).isEqualTo("Essa conta não está banida.");
		assertThat(auditorias(alvo.id(), "EXCLUSAO")).isEqualTo(1);
	}

	@Test
	void bannidoNaoComentaRevogadoVoltaEPromovidoAAdminContinuaBanido() throws Exception {
		semear(7, "fe", true);
		var admin = logado("Admin Um", Papel.ADMIN);
		var alvo = logado();
		admin.nav().post("/api/admin/blog/banimentos", corpo(alvo.id(), "motivo"));
		assertThat(comentarTexto(alvo.nav(), "fe", "olá, paz").statusCode()).isEqualTo(409);
		// promovido a admin: o ban vale para todo papel (D9)
		jdbc.update("update usuario set papel = 'ADMIN' where id = ?", alvo.id());
		limite.limpar();
		assertThat(comentarTexto(alvo.nav(), "fe", "olá, paz").statusCode()).isEqualTo(409);
		assertThat(admin.nav().delete("/api/admin/blog/banimentos/" + alvo.id()).statusCode()).isEqualTo(204);
		limite.limpar();
		assertThat(comentarTexto(alvo.nav(), "fe", "olá, paz").statusCode()).isEqualTo(201);
	}

	@Test
	void listaComFiltroENomeDoAdminNuloQuandoApagado() throws Exception {
		var admin = logado("Admin Um", Papel.ADMIN);
		var a = logado();
		var b = logado();
		admin.nav().post("/api/admin/blog/banimentos", corpo(a.id(), "motivo a"));
		admin.nav().post("/api/admin/blog/banimentos", corpo(b.id(), "motivo b"));
		var todos = json(admin.nav().get("/api/admin/blog/banimentos"));
		assertThat(todos.get("page").get("totalElements").asInt()).isEqualTo(2);
		var so = json(admin.nav().get("/api/admin/blog/banimentos?usuarioId=" + b.id()));
		assertThat(so.get("content")).hasSize(1);
		assertThat(so.get("content").get(0).get("motivo").asString()).isEqualTo("motivo b");
		assertThat(json(admin.nav().get("/api/admin/blog/banimentos?usuarioId=-1")).get("content")).isEmpty();
		jdbc.update("delete from usuario where id = ?", admin.id());
		var sem = jdbc.queryForObject("select banido_por_id from blog_banimento where usuario_id = ?", Long.class, a.id());
		assertThat(sem).isNull();
	}

	@Test
	void anonimo401EClienteDa403() throws Exception {
		var cliente = logado();
		var anonimo = new me.clama.conta.web.Navegador(porta);
		assertThat(anonimo.get("/api/admin/blog/banimentos").statusCode()).isEqualTo(401);
		assertThat(anonimo.post("/api/admin/blog/banimentos", corpo(cliente.id(), "motivo")).statusCode()).isEqualTo(401);
		assertThat(anonimo.delete("/api/admin/blog/banimentos/" + cliente.id()).statusCode()).isEqualTo(401);
		assertThat(cliente.nav().get("/api/admin/blog/banimentos").statusCode()).isEqualTo(403);
		assertThat(cliente.nav().post("/api/admin/blog/banimentos", corpo(cliente.id(), "motivo")).statusCode()).isEqualTo(403);
		assertThat(cliente.nav().delete("/api/admin/blog/banimentos/" + cliente.id()).statusCode()).isEqualTo(403);
		assertThat(quantos("blog_banimento")).isZero();
	}

	@Test
	void semDadoPessoalNoLog(org.springframework.boot.test.system.CapturedOutput saida) throws Exception {
		semear(7, "fe", true);
		var admin = logado("Admin Um", Papel.ADMIN);
		var alvo = logado();
		jdbc.update("insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, criado_em) values (7, ?, 'conteudo-secreto-xyz', false, now())", alvo.id());
		admin.nav().post("/api/admin/blog/banimentos", corpo(alvo.id(), "motivo-secreto-xyz"));
		admin.nav().get("/api/admin/blog/comentarios");
		admin.nav().get("/api/admin/blog/banimentos");
		alvo.nav().get("/api/conta/comentarios");
		admin.nav().delete("/api/admin/blog/banimentos/" + alvo.id());
		assertThat(saida.getAll()).doesNotContain(alvo.email()).doesNotContain(admin.email())
				.doesNotContain("motivo-secreto-xyz").doesNotContain("conteudo-secreto-xyz");
	}
}
