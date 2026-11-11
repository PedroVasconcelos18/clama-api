package me.clama.catalogo.interno;

import static me.clama.catalogo.Complexidade.SIMPLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import io.sentry.Sentry;
import me.clama.BancoDeTeste;
import me.clama.catalogo.InstituicaoResumo;
import me.clama.catalogo.TipoDePlano;
import me.clama.infra.web.ErroDeNegocio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ BancoDeTeste.class, CatalogoApiImpl.class, InferenciaDePlano.class })
class CatalogoApiTest {

	@Autowired CatalogoApiImpl api;
	@Autowired PlanoRepository planos;
	@Autowired InstituicaoRepository instituicoes;
	@Autowired JdbcTemplate jdbc;

	@Test
	void planoAtivoSoDevolveComumAtivo() {
		var ativo = planos.saveAndFlush(Plano.comum("Ativo", "d", 2000, SIMPLES, 1));
		var inativo = Plano.comum("Inativo", "", 3000, SIMPLES, 2);
		inativo.desativar();
		planos.saveAndFlush(inativo);
		var tecnicoId = planos.findByTipoAndAtivoTrue(TipoDePlano.GRATUITO).orElseThrow().getId();

		assertThat(api.planoAtivo(ativo.getId()).orElseThrow().valorCentavos()).isEqualTo(2000);
		assertThat(api.planoAtivo(inativo.getId())).isEmpty();
		assertThat(api.planoAtivo(tecnicoId)).isEmpty();
		assertThat(api.planoAtivo(-1L)).isEmpty();
	}

	@Test
	void gratuitoEValorLivreVemDaMigration() {
		var g = api.planoGratuito();
		assertThat(g.valorCentavos()).isZero();
		assertThat(g.tipo()).isEqualTo(TipoDePlano.GRATUITO);
		var l = api.planoValorLivre();
		assertThat(l.valorCentavos()).isEqualTo(1);
		assertThat(l.tipo()).isEqualTo(TipoDePlano.VALOR_LIVRE);
	}

	@Test
	void semPlanoGratuitoVira503ERegistraNoSentry() {
		jdbc.update("delete from plano where tipo = 'GRATUITO'");
		try (var sentry = mockStatic(Sentry.class)) {
			assertThatThrownBy(api::planoGratuito).isInstanceOfSatisfying(ErroDeNegocio.class, e -> {
				assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				assertThat(e.codigo()).isEqualTo("freemium_indisponivel");
				assertThat(e.mensagemPastoral()).isEqualTo("Estamos com problemas momentâneos. Tente em alguns minutos.");
			});
			sentry.verify(() -> Sentry.captureException(any(Throwable.class)));
		}
	}

	@Test
	void inferirUsaComumSenaoValorLivreSenao400() {
		planos.saveAndFlush(Plano.comum("Pedido", "", 2000, SIMPLES, 1));
		assertThat(api.inferirPlano(2500).nome()).isEqualTo("Pedido");
		assertThat(api.inferirPlano(1).nome()).isEqualTo("Pedido");

		planos.findAll().stream().filter(p -> !p.isTecnico()).forEach(Plano::desativar);
		planos.flush();
		assertThat(api.inferirPlano(2500).tipo()).isEqualTo(TipoDePlano.VALOR_LIVRE);

		jdbc.update("delete from plano where tipo = 'VALOR_LIVRE'");
		assertThatThrownBy(() -> api.inferirPlano(2500)).isInstanceOfSatisfying(ErroDeNegocio.class, e -> {
			assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(e.codigo()).isEqualTo("sem_plano_disponivel");
		});
	}

	@Test
	void instituicoesAtivasEmOrdemEInativaNaoSai() {
		var b = instituicoes.saveAndFlush(Instituicao.nova("B", 1));
		var a = instituicoes.saveAndFlush(Instituicao.nova("A", 1));
		var fora = Instituicao.nova("Fora", 0);
		fora.desativar();
		instituicoes.saveAndFlush(fora);

		assertThat(api.instituicoesAtivas()).containsExactly(new InstituicaoResumo(a.getId(), "A"),
				new InstituicaoResumo(b.getId(), "B"));
		assertThat(api.instituicaoAtiva(a.getId())).contains(new InstituicaoResumo(a.getId(), "A"));
		assertThat(api.instituicaoAtiva(fora.getId())).isEmpty();
	}
}
