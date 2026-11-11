package me.clama.blog.interno;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class AvisoDeConfiguracaoDoBlogTest {

	static AvisoDeConfiguracaoDoBlog aviso(String url) {
		return new AvisoDeConfiguracaoDoBlog(new BlogProps(url, Duration.ofSeconds(1)));
	}

	@Test
	void urlVaziaForaDeLocalETesteLogaErro(CapturedOutput log) {
		aviso("").conferir(new MockEnvironment().withProperty("spring.profiles.active", "prod"));
		assertThat(log.getAll()).contains("ERROR").contains("WORDPRESS_API_URL ausente");
	}

	@Test
	void urlVaziaEmLocalETesteNaoAvisa(CapturedOutput log) {
		var env = new MockEnvironment();
		env.setActiveProfiles("test");
		aviso("").conferir(env);
		assertThat(log.getAll()).doesNotContain("WORDPRESS_API_URL");
	}

	@Test
	void urlPreenchidaNaoAvisa(CapturedOutput log) {
		var env = new MockEnvironment();
		env.setActiveProfiles("prod");
		aviso("https://blog.exemplo").conferir(env);
		assertThat(log.getAll()).doesNotContain("WORDPRESS_API_URL");
	}
}
