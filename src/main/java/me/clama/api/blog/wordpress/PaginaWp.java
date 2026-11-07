package me.clama.api.blog.wordpress;

import java.util.List;

/**
 * Uma pagina da listagem e o total de paginas (header X-WP-TotalPages; ausente ou invalido = 1). semId: itens sem id
 * numerico, fora de {@code posts} (a reconciliacao os conta em posts_vistos e pula, como o int(post["id"]) do Django).
 * ponytail: id texto ("12") tambem conta como sem id; o int() do Python o aceitaria. A REST do WordPress manda numero.
 */
public record PaginaWp(List<PostWp> posts, int totalPaginas, int semId) {}
