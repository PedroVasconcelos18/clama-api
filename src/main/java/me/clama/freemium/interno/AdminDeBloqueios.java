package me.clama.freemium.interno;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Admin dos bloqueios: lista, busca pelo valor em claro (hash no servidor, nunca logado) e desbloqueio auditado sem hash. */
@Component
public class AdminDeBloqueios {

	public static final String NAO_ENCONTRADO = "Esse bloqueio não foi encontrado.";

	public record Linha(long id, Instant criadoEm, String emailHmac, String cpfHmac, boolean temIp) {}

	private final JdbcClient jdbc;
	private final HmacDeIdentificador hmac;
	private final Auditoria auditoria;

	AdminDeBloqueios(JdbcClient jdbc, HmacDeIdentificador hmac, Auditoria auditoria) {
		this.jdbc = jdbc;
		this.hmac = hmac;
		this.auditoria = auditoria;
	}

	private static final String COLUNAS = "select id, criado_em, email_hmac, cpf_hmac, ip_hmac is not null as tem_ip from freemium_bloqueio";

	@Transactional(readOnly = true)
	public Page<Linha> listar(Pageable pagina) {
		var total = jdbc.sql("select count(*) from freemium_bloqueio").query(Long.class).single();
		var linhas = jdbc.sql(COLUNAS + " order by criado_em desc, id desc limit :n offset :o")
				.param("n", pagina.getPageSize()).param("o", pagina.getOffset()).query(AdminDeBloqueios::linha).list();
		return new PageImpl<>(linhas, pagina, total);
	}

	/** Casa por e-mail ou CPF (o que vier); nenhum dos dois = 400. */
	@Transactional(readOnly = true)
	public List<Linha> buscar(String email, String cpfCnpj) {
		var temEmail = email != null && !email.isBlank();
		var temCpf = cpfCnpj != null && !cpfCnpj.replaceAll("\\D", "").isEmpty();
		if (!temEmail && !temCpf) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", "Informe o e-mail ou o CPF.",
					List.of(new ErroDeCampo("email", "Informe o e-mail ou o CPF.")));
		}
		return jdbc.sql(COLUNAS + " where email_hmac = :e or cpf_hmac = :c order by criado_em desc, id desc")
				.param("e", temEmail ? hmac.email(email) : null).param("c", temCpf ? hmac.cpf(cpfCnpj) : null)
				.query(AdminDeBloqueios::linha).list();
	}

	/** Apaga o bloqueio e audita so o id e o autor: o hash nao vai para a trilha. */
	@Transactional
	public void excluir(long autorId, long id) {
		if (jdbc.sql("delete from freemium_bloqueio where id = :id").param("id", id).update() == 0) {
			throw new ErroDeNegocio(HttpStatus.NOT_FOUND, "nao_encontrado", NAO_ENCONTRADO, NAO_ENCONTRADO);
		}
		auditoria.registrar(autorId, "freemium_bloqueio", String.valueOf(id), Acao.EXCLUSAO, Map.of());
	}

	private static Linha linha(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new Linha(rs.getLong("id"), rs.getObject("criado_em", OffsetDateTime.class).toInstant(), rs.getString("email_hmac"),
				rs.getString("cpf_hmac"), rs.getBoolean("tem_ip"));
	}
}
