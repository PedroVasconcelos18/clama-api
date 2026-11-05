package me.clama.api.identidade.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Provisorio (Task 1): tudo liberado. A Task 7 substitui. */
@Configuration
public class SegurancaConfig {

	@Bean
	SecurityFilterChain filtros(HttpSecurity http) throws Exception {
		return http.csrf(c -> c.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.build();
	}
}
