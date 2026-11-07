package me.clama.api.blog.wordpress;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Um post do WordPress com so os campos pedidos em _fields. titulo*: null quando ausente; {@code dateGmt} cru.
 */
public record PostWp(long id, String slug, String tituloRaw, String tituloRendered, String status, String dateGmt,
        String link, boolean protegidoPorSenha) {

    /** date_gmt vem sem fuso, mas e UTC; vazio ou invalido vira null (espelho.py:_data). */
    public OffsetDateTime publicadoEm() {
        if (dateGmt == null || dateGmt.isBlank()) return null;
        String s = dateGmt.strip().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            // sem fuso: UTC
        }
        try {
            return LocalDateTime.parse(s).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            // so a data: meia-noite, como o fromisoformat do Python
        }
        try {
            return LocalDate.parse(s).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
