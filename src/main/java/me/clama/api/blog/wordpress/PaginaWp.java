package me.clama.api.blog.wordpress;

import java.util.List;

/** Uma pagina da listagem e o total de paginas (header X-WP-TotalPages; ausente ou invalido = 1). */
public record PaginaWp(List<PostWp> posts, int totalPaginas) {}
