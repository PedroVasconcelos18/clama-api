package me.clama.api.blog.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erro do widget com corpo fora dos formatos do PastoralExceptionHandler (mapa §0.4): o plano, o dict dentro do campo
 * e o {"detail"} do dono. Status e corpo como sairam do Django.
 */
class RespostaDeErro extends RuntimeException {
    final int status;
    final Object corpo;

    RespostaDeErro(int status, Object corpo) {
        super(null, null, false, false);
        this.status = status;
        this.corpo = corpo;
    }

    @RestControllerAdvice(basePackageClasses = RespostaDeErro.class)
    static class Tradutor {
        @ExceptionHandler(RespostaDeErro.class)
        ResponseEntity<Object> responder(RespostaDeErro e) {
            return ResponseEntity.status(e.status).body(e.corpo);
        }
    }
}
