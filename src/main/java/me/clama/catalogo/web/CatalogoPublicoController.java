package me.clama.catalogo.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.clama.catalogo.TipoDePlano;
import me.clama.catalogo.interno.InstituicaoLogoRepository;
import me.clama.catalogo.interno.InstituicaoRepository;
import me.clama.catalogo.interno.PlanoRepository;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listas e logo publicas. O Cache-Control e posto aqui (o do Spring Security so escreve se ainda nao houver um) e o
 * Spring MVC responde 304 sozinho quando o If-None-Match bate com o ETag da resposta.
 */
@RestController
class CatalogoPublicoController {

	private static final CacheControl LISTA = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()
			.staleWhileRevalidate(Duration.ofSeconds(300));
	private static final CacheControl LOGO = CacheControl.maxAge(Duration.ofDays(1)).cachePublic().immutable();

	private final PlanoRepository planos;
	private final InstituicaoRepository instituicoes;
	private final InstituicaoLogoRepository logos;
	private final LimiteDeTaxa limite;

	CatalogoPublicoController(PlanoRepository planos, InstituicaoRepository instituicoes,
			InstituicaoLogoRepository logos, LimiteDeTaxa limite) {
		this.planos = planos;
		this.instituicoes = instituicoes;
		this.logos = logos;
		this.limite = limite;
	}

	@GetMapping("/api/planos")
	ResponseEntity<List<PlanoPublico>> planos(HttpServletRequest req) {
		limitar(req);
		var lista = planos.findByTipoAndAtivoTrueOrderByOrdemAscIdAsc(TipoDePlano.COMUM).stream()
				.map(p -> new PlanoPublico(p.getId(), p.getNome(), p.getDescricao(), p.getValorCentavos(),
						p.getComplexidade(), p.getOrdem()))
				.toList();
		return ResponseEntity.ok().cacheControl(LISTA).eTag(etag(lista.toString())).body(lista);
	}

	@GetMapping("/api/instituicoes")
	ResponseEntity<List<InstituicaoPublica>> instituicoes(HttpServletRequest req) {
		limitar(req);
		var ativas = instituicoes.findByAtivoTrueOrderByOrdemAscNomeAscIdAsc();
		// so id e hash: a lista nunca carrega os blobs
		Map<Long, String> hashes = logos.hashes(ativas.stream().map(i -> i.getId()).toList()).stream()
				.collect(Collectors.toMap(h -> h.getInstituicaoId(), h -> h.getSha256()));
		var lista = ativas.stream().map(i -> new InstituicaoPublica(i.getId(), i.getNome(),
				InstituicaoPublica.urlDaLogo(i.getId(), hashes.get(i.getId()))))
				.toList();
		return ResponseEntity.ok().cacheControl(LISTA).eTag(etag(lista.toString())).body(lista);
	}

	/** Sai tambem para instituicao inativa: pedido e repasse antigos ainda mostram a logo dela. */
	@GetMapping("/api/instituicoes/{id}/logo")
	ResponseEntity<byte[]> logo(@PathVariable Long id, HttpServletRequest req) {
		limitar(req);
		var logo = logos.findById(id).orElseThrow(() -> new ErroDeNegocio(HttpStatus.NOT_FOUND, "nao_encontrado",
				"Logo não encontrada.", "Não encontramos essa imagem."));
		return ResponseEntity.ok().cacheControl(LOGO).eTag("\"" + logo.getSha256() + "\"")
				.contentType(MediaType.parseMediaType(logo.getMime())).header("X-Content-Type-Options", "nosniff")
				.body(logo.getConteudo());
	}

	private void limitar(HttpServletRequest req) {
		limite.exigir(new Cota("catalogo:ip:" + ClienteIp.de(req), 300, Duration.ofMinutes(1)));
	}

	/** Hash do conteudo serializado (record.toString e enum.name sao estaveis entre execucoes e instancias). */
	private static String etag(String conteudo) {
		try {
			return "\"" + HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(conteudo.getBytes(StandardCharsets.UTF_8))) + "\"";
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
