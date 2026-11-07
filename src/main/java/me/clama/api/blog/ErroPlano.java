package me.clama.api.blog;

/**
 * O formato plano de erro do blog (mapa §0.4, item 3): {"code","pastoral_message"}, sem "error" nem "message". 403 de
 * banido, 429 do widget e 400 comentario_muito_antigo.
 */
public record ErroPlano(String code, String pastoral_message) {}
