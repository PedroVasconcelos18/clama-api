package me.clama.infra.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RotasDeTeste {

    record Entrada(@NotBlank String nome, @Email String email) {}

    @PostMapping("/teste/validacao")
    void validacao(@Valid @RequestBody Entrada entrada) {}

    @GetMapping("/teste/negocio")
    void negocio() {
        throw new ErroDeNegocio(HttpStatus.CONFLICT, "ja_pago", "Pedido já pago.", "Este pedido já foi pago.");
    }

    @GetMapping("/teste/falha")
    void falha() {
        throw new IllegalStateException("segredo interno");
    }
}
