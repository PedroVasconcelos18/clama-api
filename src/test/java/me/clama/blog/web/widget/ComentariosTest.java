package me.clama.blog.web.widget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.clama.blog.interno.ComentarioRepository;
import me.clama.conta.interno.Papel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** D7: validacao em code points, moderacao, IP cifrado, limite, ordem das barreiras e nome publico. */
class ComentariosTest extends WidgetTeste {

	@Autowired ComentarioRepository comentarios;

	private String ultimoConteudo() {
		return jdbc.queryForObject("select conteudo from blog_comentario", String.class);
	}

	@Test
	void conteudoApadoEGravadoSemAsPontas() throws Exception {
		semear(7, "fe", true);
		var res = comentarTexto(logado().nav(), "fe", "  \n Belo texto  \t");
		assertThat(res.statusCode()).isEqualTo(201);
		assertThat(json(res).get("conteudo").asString()).isEqualTo("Belo texto");
		assertThat(ultimoConteudo()).isEqualTo("Belo texto");
	}

	@Test
	void tresECemDoisMilNoLimiteContadosEmCodePoints() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		var emoji = "😀"; // 1 code point, 2 chars: contar chars erraria os dois limites
		assertThat(comentarTexto(l.nav(), "fe", "abc").statusCode()).isEqualTo(201);
		assertThat(comentarTexto(l.nav(), "fe", emoji.repeat(3)).statusCode()).isEqualTo(201);
		limite.limpar();
		assertThat(comentarTexto(l.nav(), "fe", emoji.repeat(2)).statusCode()).isEqualTo(400); // 2 code points (4 chars)
		assertThat(comentarTexto(l.nav(), "fe", "ab").statusCode()).isEqualTo(400);
		assertThat(comentarTexto(l.nav(), "fe", emoji.repeat(2000)).statusCode()).isEqualTo(201); // 4000 chars
		limite.limpar();
		assertThat(comentarTexto(l.nav(), "fe", "a".repeat(2000)).statusCode()).isEqualTo(201);
		assertThat(json(comentarTexto(l.nav(), "fe", emoji.repeat(2001))).get("code").asString()).isEqualTo("comentario_longo");
		assertThat(json(comentarTexto(l.nav(), "fe", "a".repeat(2001))).get("code").asString()).isEqualTo("comentario_longo");
		limite.limpar();
		// aparar vem antes de contar: 2000 + espacos cabe, e 2 letras com espacos em volta nao
		assertThat(comentarTexto(l.nav(), "fe", "  " + "a".repeat(2000) + "  ").statusCode()).isEqualTo(201);
		assertThat(comentarTexto(l.nav(), "fe", "   ab   ").statusCode()).isEqualTo(400);
		assertThat(comentarTexto(l.nav(), "fe", "abc\u0000def").statusCode()).isEqualTo(400); // NUL nao cabe no Postgres
	}

	@Test
	void suspeitoEMarcadoPelaModeracao() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		comentarTexto(l.nav(), "fe", "Belo texto pastoral");
		comentarTexto(l.nav(), "fe", "Que merda de texto");
		assertThat(jdbc.queryForList("select suspeito from blog_comentario order by id", Boolean.class)).containsExactly(false, true);
	}

	@Test
	void ipDoXffValidoFicaCifrado() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		var res = l.nav().post("/api/blog/posts/fe/comments/", "{\"conteudo\":\"Belo texto\"}", "X-CSRFToken", csrf(l.nav()),
				"X-Forwarded-For", "203.0.113.9, 10.0.0.1");
		assertThat(res.statusCode()).isEqualTo(201);
		assertThat(comentarios.findAll().getFirst().getIp()).isEqualTo("203.0.113.9");
		var noBanco = jdbc.queryForObject("select ip_cifrado from blog_comentario", String.class);
		assertThat(noBanco).isNotBlank().doesNotContain("203.0.113.9");
	}

	@Test
	void xffLixoCaiNoRemoteAddr() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		l.nav().post("/api/blog/posts/fe/comments/", "{\"conteudo\":\"Belo texto\"}", "X-CSRFToken", csrf(l.nav()),
				"X-Forwarded-For", "lixo, <script>");
		var ip = comentarios.findAll().getFirst().getIp();
		// com forward-headers-strategy native o Tomcat troca o remoteAddr pelo lixo do XFF; o que nao e IP nao pode ser gravado
		if (ip != null) assertThat(InetAddress.ofLiteral(ip).isLoopbackAddress()).isTrue();
		assertThat(ip == null || ip.matches("[0-9a-f:.]+")).isTrue();
		assertThat(quantos("blog_comentario")).isEqualTo(1);
	}

	@Test
	void banidoRecebe409AntesDaValidacaoEDoLimite() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		banir(l.id());
		for (var corpo : List.of("{\"conteudo\":\"ab\"}", "{}", "nao e json", "{\"conteudo\":\"Belo texto\"}")) {
			var res = comentar(l.nav(), "fe", corpo);
			assertThat(res.statusCode()).as(corpo).isEqualTo(409);
			assertThat(json(res).get("code").asString()).isEqualTo("comentarios_suspensos");
		}
		assertThat(quantos("blog_comentario")).isZero();
	}

	@Test
	void postNaoPublicadoNaoGrava() throws Exception {
		semear(7, "fora", false);
		assertThat(comentarTexto(logado().nav(), "fora", "Belo texto").statusCode()).isEqualTo(409);
		assertThat(quantos("blog_comentario")).isZero();
	}

	@Test
	void limiteEPorUsuario() throws Exception {
		semear(7, "fe", true);
		var a = logado();
		var b = logado();
		for (int i = 0; i < 5; i++) comentarTexto(a.nav(), "fe", "Comentario " + i);
		assertThat(comentarTexto(a.nav(), "fe", "Comentario 6").statusCode()).isEqualTo(429);
		assertThat(comentarTexto(b.nav(), "fe", "Comentario 6").statusCode()).isEqualTo(201);
	}

	@Test
	void comentarioInvalidoTambemGastaAJanela() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		for (int i = 0; i < 5; i++) assertThat(comentarTexto(l.nav(), "fe", "x").statusCode()).isEqualTo(400);
		assertThat(comentarTexto(l.nav(), "fe", "Belo texto").statusCode()).isEqualTo(429);
	}

	@Test
	void nomePublicoRespeitaOFormatoCompletoEOPedro() throws Exception {
		semear(7, "fe", true);
		var l = logado("Ana Maria de Souza", Papel.CLIENTE);
		assertThat(json(comentarTexto(l.nav(), "fe", "Belo texto")).get("customer_nome").asString()).isEqualTo("Ana S.");
		jdbc.update("update usuario set nome_formato_blog = 'completo' where id = ?", l.id());
		limite.limpar();
		assertThat(json(comentarTexto(l.nav(), "fe", "Belo texto")).get("customer_nome").asString()).isEqualTo("Ana Maria de Souza");
		var s = logado("", Papel.CLIENTE);
		assertThat(json(comentarTexto(s.nav(), "fe", "Belo texto")).get("customer_nome").asString()).isEqualTo("Pedro");
		var lista = json(new me.clama.conta.web.Navegador(porta).get("/api/blog/posts/fe/comments/")).get("results");
		assertThat(lista).extracting(n -> n.get("customer_nome").asString()).containsExactlyInAnyOrder("Ana Maria de Souza", "Ana Maria de Souza", "Pedro");
	}

	/** Carry da T1: o espelho pode ir ao WordPress (ate 10 s); nunca com uma transacao (e uma conexao) aberta. */
	@Test
	void espelhoNuncaEResolvidoDentroDeTransacao() throws Exception {
		var vistos = new CopyOnWriteArrayList<Boolean>();
		doAnswer(inv -> {
			vistos.add(TransactionSynchronizationManager.isActualTransactionActive());
			return inv.callRealMethod();
		}).when(wordpress).buscarPorSlug(anyString());
		wpTem("um", wpPost(11, "um", false));
		wpTem("dois", wpPost(12, "dois", false));
		var l = logado();
		assertThat(comentarTexto(l.nav(), "um", "Belo texto").statusCode()).isEqualTo(201);
		assertThat(curtir(l.nav(), "dois").statusCode()).isEqualTo(200);
		assertThat(vistos).containsExactly(false, false);
	}
}
