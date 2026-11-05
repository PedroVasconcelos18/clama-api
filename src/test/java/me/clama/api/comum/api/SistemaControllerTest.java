package me.clama.api.comum.api;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class SistemaControllerTest {

	@Autowired
	MockMvc mvc;

	@Test
	void healthDevolveContratoDjango() throws Exception {
		mvc.perform(get("/api/health/"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"))
				.andExpect(jsonPath("$.version").value("0.1.0"))
				.andExpect(jsonPath("$.database").value("ok"))
				.andExpect(jsonPath("$.timestamp", endsWith("Z")));
	}
}
