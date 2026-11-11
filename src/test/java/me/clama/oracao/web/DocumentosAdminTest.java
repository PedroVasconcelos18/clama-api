package me.clama.oracao.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.oracao.interno.AdminDeDocumentos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** Admin de documentos de contexto (D14): metadados no banco, arquivo na Files API (WireMock), corpo cru ate 100 MB. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
class DocumentosAdminTest {

	static final WireMockServer CLAUDE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String RAIZ = "/api/admin/documentos";
	static final long CEM_MB = 100L * 1024 * 1024;
	static final String META = """
			{"id": "%s", "type": "file", "filename": "x", "mime_type": "%s", "size_bytes": 10,
			 "created_at": "2026-11-12T10:00:00Z", "downloadable": false}""";
	static final String NAO_ACHADO = """
			{"type": "error", "error": {"type": "not_found_error", "message": "File not found"}}""";
	static final String FALHA = """
			{"type": "error", "error": {"type": "api_error", "message": "boom"}}""";

	static {
		CLAUDE.start();
	}

	@DynamicPropertySource
	static void claude(DynamicPropertyRegistry r) {
		r.add("clama.oracao.anthropic-url", CLAUDE::baseUrl);
		r.add("clama.oracao.anthropic-api-key", () -> "sk-ant-teste");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired AdminDeDocumentos documentos;

	@BeforeEach
	@AfterEach
	void limpar() {
		CLAUDE.resetAll();
		jdbc.update("delete from auditoria where entidade = 'documento_contexto'");
		jdbc.update("delete from documento_contexto");
		jdbc.update("delete from usuario where email like 'adm-%@exemplo.com'");
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		assertThat(new Navegador(porta).get(RAIZ).statusCode()).isEqualTo(401);
		assertThat(entrar(Papel.CLIENTE).get(RAIZ).statusCode()).isEqualTo(403);
	}

	@Test
	void criaEListaMetadadosSemArquivo() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "Catecismo");

		var lista = lista(adm);
		assertThat(lista).hasSize(1);
		var d = lista.get(0);
		assertThat(d.get("id").asLong()).isEqualTo(id);
		assertThat(d.get("nome").asString()).isEqualTo("Catecismo");
		assertThat(d.get("ativo").asBoolean()).isFalse();
		assertThat(d.get("fileId").isNull()).isTrue();
		assertThat(d.get("mime").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'documento_contexto' and acao = 'ADICAO'",
				Integer.class)).isEqualTo(1);
		assertThat(adm.post(RAIZ, "{\"nome\": \" \"}").statusCode()).isEqualTo(400);
	}

	@Test
	void sobePdfEGuardaOFileId() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "Catecismo");
		CLAUDE.stubFor(post("/v1/files").willReturn(okJson(META.formatted("file_pdf", "application/pdf"))));
		var pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n";

		var res = adm.putCru(RAIZ + "/" + id + "/arquivo", "application/pdf", HttpRequest.BodyPublishers.ofString(pdf));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var d = Navegador.json(res);
		assertThat(d.get("fileId").asString()).isEqualTo("file_pdf");
		assertThat(d.get("mime").asString()).isEqualTo("application/pdf");
		assertThat(d.get("tamanhoBytes").asLong()).isEqualTo(pdf.length());
		CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/files")).withRequestBody(containing("Content-Type: application/pdf"))
				.withRequestBody(containing("%PDF-1.7")).withRequestBody(containing("filename=\"documento-" + id + ".pdf\"")));
		assertThat(jdbc.queryForObject("select file_id from documento_contexto where id = ?", String.class, id)).isEqualTo("file_pdf");
	}

	@Test
	void sobeTxtMesmoComContentTypeMentindo() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "Salmos");
		CLAUDE.stubFor(post("/v1/files").willReturn(okJson(META.formatted("file_txt", "text/plain"))));

		var res = adm.putCru(RAIZ + "/" + id + "/arquivo", "application/octet-stream",
				HttpRequest.BodyPublishers.ofString("Senhor, tem piedade. Ação de graças.", StandardCharsets.UTF_8));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(Navegador.json(res).get("mime").asString()).isEqualTo("text/plain");
		CLAUDE.verify(postRequestedFor(urlEqualTo("/v1/files")).withRequestBody(containing("Content-Type: text/plain")));
	}

	/** DOCX (zip), PNG, texto que nao e UTF-8 e corpo vazio: 400 documento_invalido, nada vai para a Files API. */
	@Test
	void formatosInvalidosViram400SemChamarAAnthropic() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "X");
		byte[] docx = { 'P', 'K', 3, 4, 20, 0, 6, 0, 8, 0, 0, 0, '!', 0 };
		byte[] png = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R' };
		byte[] latin1 = "Ação".getBytes(StandardCharsets.ISO_8859_1);
		for (var corpo : List.of(docx, png, latin1, new byte[0])) {
			var res = adm.putCru(RAIZ + "/" + id + "/arquivo", "application/octet-stream",
					HttpRequest.BodyPublishers.ofByteArray(corpo));
			confere(res, 400, "documento_invalido");
		}
		CLAUDE.verify(0, postRequestedFor(anyUrl()));
		assertThat(jdbc.queryForObject("select file_id from documento_contexto where id = ?", String.class, id)).isNull();
	}

	@Test
	void uploadEmDocumentoInexistenteE404() throws Exception {
		confere(entrar(Papel.ADMIN).putCru(RAIZ + "/999999/arquivo", "application/pdf", HttpRequest.BodyPublishers.ofString("%PDF-1")),
				404, "documento_nao_encontrado");
	}

	/** Corpo cru acima do multipart (300 KB) e de 2 MB passa: nenhum limite do Tomcat/Spring corta antes dos 100 MB. */
	@Test
	void arquivoDeVariosMegabytesPassa() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "Grande");
		CLAUDE.stubFor(post("/v1/files").willReturn(okJson(META.formatted("file_grande", "text/plain"))));

		var res = adm.putCru(RAIZ + "/" + id + "/arquivo", "text/plain", gerado(5L * 1024 * 1024, 'a'));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(Navegador.json(res).get("tamanhoBytes").asLong()).isEqualTo(5L * 1024 * 1024);
	}

	@Test
	void exatamente100MbPassaE100MbMais1ViraRecusado() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "Limite");
		CLAUDE.stubFor(post("/v1/files").willReturn(okJson(META.formatted("file_100", "text/plain"))));

		var acima = adm.putCru(RAIZ + "/" + id + "/arquivo", "text/plain", gerado(CEM_MB + 1, 'a'));

		confere(acima, 400, "documento_grande_demais");
		CLAUDE.verify(0, postRequestedFor(anyUrl()));
		assertThat(jdbc.queryForObject("select file_id from documento_contexto where id = ?", String.class, id)).isNull();

		var no_limite = adm.putCru(RAIZ + "/" + id + "/arquivo", "text/plain", gerado(CEM_MB, 'a'));
		assertThat(no_limite.statusCode()).as(no_limite.body()).isEqualTo(200);
		assertThat(Navegador.json(no_limite).get("tamanhoBytes").asLong()).isEqualTo(CEM_MB);
	}

	@Test
	void falhaDaFilesApiNoUploadE502SemGravar() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "X");
		CLAUDE.stubFor(post("/v1/files").willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody(FALHA)));

		var res = adm.putCru(RAIZ + "/" + id + "/arquivo", "text/plain", HttpRequest.BodyPublishers.ofString("texto"));

		confere(res, 502, "anthropic_falhou");
		assertThat(res.body()).doesNotContain("boom");
		assertThat(jdbc.queryForObject("select file_id from documento_contexto where id = ?", String.class, id)).isNull();
	}

	@Test
	void novoUploadSubstituiEApagaOArquivoAntigo() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "X");
		jdbc.update("update documento_contexto set file_id = 'file_velho', mime = 'text/plain', tamanho_bytes = 1 where id = ?", id);
		CLAUDE.stubFor(post("/v1/files").willReturn(okJson(META.formatted("file_novo", "text/plain"))));
		CLAUDE.stubFor(delete("/v1/files/file_velho").willReturn(okJson("{\"id\": \"file_velho\", \"type\": \"file_deleted\"}")));

		assertThat(adm.putCru(RAIZ + "/" + id + "/arquivo", "text/plain", HttpRequest.BodyPublishers.ofString("novo")).statusCode())
				.isEqualTo(200);

		assertThat(jdbc.queryForObject("select file_id from documento_contexto where id = ?", String.class, id)).isEqualTo("file_novo");
		CLAUDE.verify(1, deleteRequestedFor(urlEqualTo("/v1/files/file_velho")));
	}

	@Test
	void ativarConfereOsMetadadosNaFilesApi() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = comArquivo("file_ok");
		CLAUDE.stubFor(get("/v1/files/file_ok").willReturn(okJson(META.formatted("file_ok", "text/plain"))));

		var res = adm.patch(RAIZ + "/" + id, "{\"ativo\": true}");

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(Navegador.json(res).get("ativo").asBoolean()).isTrue();
		CLAUDE.verify(1, getRequestedFor(urlEqualTo("/v1/files/file_ok")));
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'documento_contexto' and acao = 'MUDANCA'",
				Integer.class)).isEqualTo(1);

		var off = adm.patch(RAIZ + "/" + id, "{\"ativo\": false}");
		assertThat(Navegador.json(off).get("ativo").asBoolean()).isFalse();
		CLAUDE.verify(1, getRequestedFor(urlEqualTo("/v1/files/file_ok"))); // desativar nao consulta
	}

	@Test
	void ativarComArquivoSumidoNaAnthropicE409() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = comArquivo("file_sumiu");
		CLAUDE.stubFor(get("/v1/files/file_sumiu").willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
				.withBody(NAO_ACHADO)));

		confere(adm.patch(RAIZ + "/" + id, "{\"ativo\": true}"), 409, "documento_arquivo_sumiu");
		assertThat(ativos()).isEmpty();
	}

	@Test
	void ativarSemArquivoE409EFalhaDaApiE502() throws Exception {
		var adm = entrar(Papel.ADMIN);
		confere(adm.patch(RAIZ + "/" + criar(adm, "Vazio"), "{\"ativo\": true}"), 409, "documento_sem_arquivo");

		var id = comArquivo("file_x");
		CLAUDE.stubFor(get("/v1/files/file_x").willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody(FALHA)));
		confere(adm.patch(RAIZ + "/" + id, "{\"ativo\": true}"), 502, "anthropic_falhou");
		assertThat(ativos()).isEmpty();
		assertThat(adm.patch(RAIZ + "/999999", "{\"ativo\": true}").statusCode()).isEqualTo(404);
		assertThat(adm.patch(RAIZ + "/" + id, "{}").statusCode()).isEqualTo(400);
		assertThat(adm.patch(RAIZ + "/" + id, "{\"nome\": \" \"}").statusCode()).isEqualTo(400);
	}

	@Test
	void apagarApagaNaAnthropicEDepoisNoBanco() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = comArquivo("file_del");
		CLAUDE.stubFor(delete("/v1/files/file_del").willReturn(okJson("{\"id\": \"file_del\", \"type\": \"file_deleted\"}")));

		assertThat(adm.delete(RAIZ + "/" + id).statusCode()).isEqualTo(204);

		CLAUDE.verify(1, deleteRequestedFor(urlEqualTo("/v1/files/file_del")));
		assertThat(lista(adm)).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'documento_contexto' and acao = 'EXCLUSAO'",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void apagarComOArquivoJaSumidoNaAnthropicTambemApagaOLocal() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = comArquivo("file_404");
		CLAUDE.stubFor(delete("/v1/files/file_404").willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
				.withBody(NAO_ACHADO)));

		assertThat(adm.delete(RAIZ + "/" + id).statusCode()).isEqualTo(204);
		assertThat(lista(adm)).isEmpty();
	}

	@Test
	void apagarComFalhaDaAnthropicMantemALinha() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = comArquivo("file_falha");
		CLAUDE.stubFor(delete("/v1/files/file_falha").willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
				.withBody(FALHA)));

		confere(adm.delete(RAIZ + "/" + id), 502, "anthropic_falhou");

		assertThat(lista(adm)).hasSize(1);
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'documento_contexto' and acao = 'EXCLUSAO'",
				Integer.class)).isZero();
	}

	@Test
	void apagarSemArquivoNaoChamaAAnthropicEInexistenteE404() throws Exception {
		var adm = entrar(Papel.ADMIN);
		assertThat(adm.delete(RAIZ + "/" + criar(adm, "Vazio")).statusCode()).isEqualTo(204);
		assertThat(adm.delete(RAIZ + "/999999").statusCode()).isEqualTo(404);
		CLAUDE.verify(0, deleteRequestedFor(anyUrl()));
	}

	/** O que a geracao usa: so os ativos, por id. */
	@Test
	void fileIdsAtivosEmOrdemDeId() throws Exception {
		var b = comArquivo("file_b");
		var a = comArquivo("file_a");
		comArquivo("file_inativo");
		jdbc.update("update documento_contexto set ativo = true where id in (?, ?)", a, b);

		assertThat(documentos.fileIdsAtivos()).containsExactly("file_b", "file_a");
	}

	@Test
	void renomearPeloPatchEAuditado() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "Antigo");

		var res = adm.patch(RAIZ + "/" + id, "{\"nome\": \"  Novo \"}");

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(Navegador.json(res).get("nome").asString()).isEqualTo("Novo");
		assertThat(jdbc.queryForObject("select diff -> 'nome' ->> 'depois' from auditoria where entidade = 'documento_contexto' and acao = 'MUDANCA'",
				String.class)).isEqualTo("Novo");
		CLAUDE.verify(0, getRequestedFor(anyUrl())); // so renomear nao consulta a Anthropic
	}

	@Test
	void contentLengthAcimaDe100MbRecusaSemLerOCorpo() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "X");
		var e = org.junit.jupiter.api.Assertions.assertThrows(me.clama.infra.web.ErroDeNegocio.class,
				() -> documentos.receberArquivo(1L, id, InputStream.nullInputStream(), CEM_MB + 1));
		assertThat(e.codigo()).isEqualTo("documento_grande_demais");
		assertThat(e.status().value()).isEqualTo(400);
		CLAUDE.verify(0, postRequestedFor(anyUrl()));
	}

	/** Dois uploads ocupam as vagas (a Anthropic demora); o terceiro recebe 503 na hora e o espaco volta depois. */
	@Test
	void terceiroUploadSimultaneoRecebe503() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var a = criar(adm, "A");
		var b = criar(adm, "B");
		var c = criar(adm, "C");
		CLAUDE.stubFor(post("/v1/files").inScenario("l").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
				.willReturn(okJson(META.formatted("file_l1", "text/plain")).withFixedDelay(1500)).willSetStateTo("2"));
		CLAUDE.stubFor(post("/v1/files").inScenario("l").whenScenarioStateIs("2")
				.willReturn(okJson(META.formatted("file_l2", "text/plain")).withFixedDelay(1500)));
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var f1 = pool.submit(() -> entrar(Papel.ADMIN).putCru(RAIZ + "/" + a + "/arquivo", "text/plain", HttpRequest.BodyPublishers.ofString("um")));
			var f2 = pool.submit(() -> entrar(Papel.ADMIN).putCru(RAIZ + "/" + b + "/arquivo", "text/plain", HttpRequest.BodyPublishers.ofString("dois")));
			org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/files"))).size() == 2);

			confere(adm.putCru(RAIZ + "/" + c + "/arquivo", "text/plain", HttpRequest.BodyPublishers.ofString("tres")), 503, "documento_upload_ocupado");
			f1.get();
			f2.get();
		} finally {
			pool.shutdownNow();
		}
		CLAUDE.resetScenarios();
		CLAUDE.stubFor(post("/v1/files").willReturn(okJson(META.formatted("file_c", "text/plain"))));
		assertThat(adm.putCru(RAIZ + "/" + c + "/arquivo", "text/plain", HttpRequest.BodyPublishers.ofString("tres")).statusCode()).isEqualTo(200);
	}

	/** Dois PUTs no mesmo documento: um grava, o outro apaga o arquivo que subiu e recebe 409 (nada orfao na Anthropic). */
	@Test
	void dois_uploadsConcorrentesNoMesmoDocumentoDeixamUmSo() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "Corrida");
		CLAUDE.stubFor(post("/v1/files").inScenario("c").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
				.willReturn(okJson(META.formatted("file_1", "text/plain")).withFixedDelay(800)).willSetStateTo("2"));
		CLAUDE.stubFor(post("/v1/files").inScenario("c").whenScenarioStateIs("2")
				.willReturn(okJson(META.formatted("file_2", "text/plain")).withFixedDelay(800)));
		CLAUDE.stubFor(delete(urlPathMatching("/v1/files/file_[12]")).willReturn(okJson("{\"id\": \"x\", \"type\": \"file_deleted\"}")));
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		List<Integer> status = new ArrayList<>();
		try {
			var n1 = entrar(Papel.ADMIN);
			var n2 = entrar(Papel.ADMIN);
			var f1 = pool.submit(() -> n1.putCru(RAIZ + "/" + id + "/arquivo", "text/plain", HttpRequest.BodyPublishers.ofString("um")).statusCode());
			var f2 = pool.submit(() -> n2.putCru(RAIZ + "/" + id + "/arquivo", "text/plain", HttpRequest.BodyPublishers.ofString("dois")).statusCode());
			status.add(f1.get());
			status.add(f2.get());
		} finally {
			pool.shutdownNow();
		}

		assertThat(status).containsExactlyInAnyOrder(200, 409);
		var ficou = jdbc.queryForObject("select file_id from documento_contexto where id = ?", String.class, id);
		var perdeu = ficou.equals("file_1") ? "file_2" : "file_1";
		CLAUDE.verify(1, deleteRequestedFor(urlEqualTo("/v1/files/" + perdeu)));
		CLAUDE.verify(0, deleteRequestedFor(urlEqualTo("/v1/files/" + ficou)));
	}

	@Test
	void multipartNaRotaDoArquivoE415() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var id = criar(adm, "X");
		var res = adm.putArquivo(RAIZ + "/" + id + "/arquivo", "arquivo", "a.txt", "text/plain", "texto".getBytes(StandardCharsets.UTF_8));
		assertThat(res.statusCode()).as(res.body()).isEqualTo(415);
		CLAUDE.verify(0, postRequestedFor(anyUrl()));
	}

	@Test
	void bancoRecusaFileIdRepetidoEMimeForaDaLista() {
		comArquivo("file_dup");
		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> comArquivo("file_dup"));
		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
				() -> jdbc.update("update documento_contexto set mime = 'image/png'"));
	}

	// ---- apoio

	static HttpRequest.BodyPublisher gerado(long bytes, char c) {
		return HttpRequest.BodyPublishers.ofInputStream(() -> new InputStream() {
			long restante = bytes;

			@Override
			public int read() {
				return restante-- > 0 ? c : -1;
			}

			@Override
			public int read(byte[] b, int off, int len) {
				if (restante <= 0) return -1;
				int n = (int) Math.min(len, restante);
				java.util.Arrays.fill(b, off, off + n, (byte) c);
				restante -= n;
				return n;
			}
		});
	}

	long comArquivo(String fileId) {
		return jdbc.queryForObject("""
				insert into documento_contexto (nome, mime, tamanho_bytes, file_id, criado_em, atualizado_em)
				values ('Doc', 'text/plain', 10, ?, now(), now()) returning id""", Long.class, fileId);
	}

	List<Long> ativos() {
		return jdbc.queryForList("select id from documento_contexto where ativo", Long.class);
	}

	static void confere(HttpResponse<String> res, int status, String codigo) {
		assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
		assertThat(Navegador.json(res).get("codigo").asString()).isEqualTo(codigo);
	}

	long criar(Navegador adm, String nome) throws Exception {
		var res = adm.post(RAIZ, "{\"nome\": \"" + nome + "\"}");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
		return Navegador.json(res).get("id").asLong();
	}

	List<JsonNode> lista(Navegador nav) throws Exception {
		var res = nav.get(RAIZ);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var l = new ArrayList<JsonNode>();
		Navegador.json(res).forEach(l::add);
		return l;
	}

	Navegador entrar(Papel papel) throws Exception {
		var email = "adm-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", papel);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}
}
