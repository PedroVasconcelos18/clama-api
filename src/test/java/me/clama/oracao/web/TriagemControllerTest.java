package me.clama.oracao.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.util.Map;
import java.util.Optional;
import me.clama.BancoDeTeste;
import me.clama.conta.web.Navegador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.oracao.interno.Triagem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** {@code POST /api/triagem}: anonimo com CSRF, 20 por hora por IP, texto de ate 2000 caracteres. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class TriagemControllerTest {

	@LocalServerPort int porta;
	@Autowired LimiteDeTaxa limite;
	@MockitoBean Triagem triagem;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
	}

	Navegador anonimo() throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		return nav;
	}

	static String corpo(String texto) {
		return Navegador.JSON.writeValueAsString(Map.of("texto", texto));
	}

	@Test
	void limiteDe20PorHoraPorIp() throws Exception {
		when(triagem.avaliar(any())).thenReturn(Optional.of(false));
		var nav = anonimo();
		for (int i = 0; i < 20; i++) {
			var res = nav.post("/api/triagem", corpo("Pela minha família"));
			assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
			assertThat(res.body()).isEqualTo("{\"risco\":false}");
		}

		assertThat(nav.post("/api/triagem", corpo("Pela minha família")).statusCode()).isEqualTo(429);
	}

	@Test
	void textoAcimaDe2000CaracteresOuEmBrancoE400() throws Exception {
		var nav = anonimo();
		assertThat(nav.post("/api/triagem", corpo("a".repeat(2001))).statusCode()).isEqualTo(400);
		assertThat(nav.post("/api/triagem", corpo("  ")).statusCode()).isEqualTo(400);
	}

	@Test
	void semCsrfE403() throws Exception {
		assertThat(new Navegador(porta).post("/api/triagem", corpo("Pela minha família")).statusCode()).isEqualTo(403);
	}
}
