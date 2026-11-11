package me.clama.blog.web.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import me.clama.blog.interno.Curtidas;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** D8: a curtida alterna, conta varios usuarios e o clique duplo nunca da 500. */
class CurtidasTest extends WidgetTeste {

	@Autowired Curtidas curtidas;
	@Autowired TransactionTemplate transacao;

	@Test
	void alternaECurtidoDescurtido() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		var a = json(curtir(l.nav(), "fe"));
		assertThat(a.get("liked").asBoolean()).isTrue();
		assertThat(a.get("like_count").asLong()).isEqualTo(1);
		var b = json(curtir(l.nav(), "fe"));
		assertThat(b.get("liked").asBoolean()).isFalse();
		assertThat(b.get("like_count").asLong()).isZero();
		assertThat(quantos("blog_curtida")).isZero();
	}

	@Test
	void contagemComVariosUsuariosEPorPost() throws Exception {
		semear(7, "fe", true);
		semear(8, "outro", true);
		var a = logado();
		var b = logado();
		var c = logado();
		curtir(a.nav(), "fe");
		curtir(b.nav(), "fe");
		curtir(c.nav(), "outro");
		assertThat(json(curtir(c.nav(), "fe")).get("like_count").asLong()).isEqualTo(3);
		assertThat(json(curtir(a.nav(), "fe")).get("like_count").asLong()).isEqualTo(2); // a descurtiu
		assertThat(json(curtir(a.nav(), "outro")).get("like_count").asLong()).isEqualTo(2);
	}

	@Test
	void limiteDeTrintaEPorUsuario() throws Exception {
		semear(7, "fe", true);
		var a = logado();
		var b = logado();
		for (int i = 0; i < 30; i++) curtir(a.nav(), "fe");
		assertThat(curtir(a.nav(), "fe").statusCode()).isEqualTo(429);
		assertThat(curtir(b.nav(), "fe").statusCode()).isEqualTo(200);
	}

	/** O outro clique ainda nao comitou a linha: o delete nao a ve, o insert espera e o ON CONFLICT DO NOTHING absorve. */
	@Test
	void cliqueDuploComLinhaAindaNaoComitadaDaUmaLinhaESemErro() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		var pool = Executors.newSingleThreadExecutor();
		try {
			var inseriu = new CountDownLatch(1);
			var soltar = new CountDownLatch(1);
			var primeiro = pool.submit(() -> transacao.execute(s -> {
				jdbc.update("insert into blog_curtida (post_id, usuario_id, criado_em) values (7, ?, now())", l.id());
				inseriu.countDown();
				try {
					soltar.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return null;
			}));
			assertThat(inseriu.await(10, TimeUnit.SECONDS)).isTrue();
			var segundo = Executors.newSingleThreadExecutor().submit(() -> curtidas.alternar(l.id(), "fe"));
			Thread.sleep(500); // o segundo esta bloqueado no insert
			assertThat(segundo.isDone()).isFalse();
			soltar.countDown();
			primeiro.get(10, TimeUnit.SECONDS);
			var r = segundo.get(10, TimeUnit.SECONDS);
			assertThat(r.curtido()).isTrue();
			assertThat(r.total()).isEqualTo(1);
			assertThat(quantos("blog_curtida")).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void cliquesSimultaneosNuncaDao500NemDuplicam() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		for (int rodada = 0; rodada < 15; rodada++) {
			jdbc.update("delete from blog_curtida");
			limite.limpar();
			var pool = Executors.newFixedThreadPool(2);
			var largada = new CountDownLatch(1);
			var futuros = new ArrayList<java.util.concurrent.Future<Curtidas.Resultado>>();
			for (int i = 0; i < 2; i++) futuros.add(pool.submit(() -> {
				largada.await();
				return curtidas.alternar(l.id(), "fe");
			}));
			largada.countDown();
			for (var f : futuros) f.get(10, TimeUnit.SECONDS); // excecao aqui = teste vermelho
			pool.shutdown();
			assertThat(quantos("blog_curtida")).isLessThanOrEqualTo(1);
		}
	}
}
