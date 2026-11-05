package me.clama.api.identidade.token;

public class JwtInvalido extends RuntimeException {
    public JwtInvalido(String msg) {
        super(msg);
    }
}
