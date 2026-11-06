package me.clama.api.comum.erro;

import java.util.LinkedHashMap;
import java.util.Map;

/** ClamaBaseException do Django: vira {"error":{code,message,pastoral_message,...extra}}. */
public class PastoralException extends RuntimeException {
    private final int status;
    private final String code;
    private final String pastoralMessage;
    private final Map<String, Object> extra;

    public PastoralException(int status, String code, String message, String pastoralMessage, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.pastoralMessage = pastoralMessage;
        this.extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public PastoralException(int status, String code, String message, String pastoralMessage) {
        this(status, code, message, pastoralMessage, Map.of());
    }

    public static PastoralException naoAutenticado() {
        return new PastoralException(401, "not_authenticated", "Authentication required", "Faça login pra continuar.");
    }

    public static PastoralException csrfInvalido() {
        return new PastoralException(403, "csrf_invalido", "CSRF verification failed",
                "Não conseguimos confirmar que este pedido veio de você. Recarregue a página e tente de novo.");
    }

    /** CustomerPasswordChangeRequiredError (IsCustomerPasswordCurrent de clama/core/permissions.py). */
    public static PastoralException senhaTemporaria() {
        return new PastoralException(403, "customer_force_change_password",
                "User must change temporary password before continuing", "Antes de continuar, atualize sua senha.");
    }

    public static PastoralException limiteExcedido(long segundos) {
        return new PastoralException(429, "rate_limited", "Too many requests. Retry after " + segundos + " seconds.",
                "Você fez vários pedidos seguidos — espera um instante e tenta de novo, com calma.");
    }

    public int status() {
        return status;
    }

    public Map<String, Object> corpo() {
        Map<String, Object> erro = new LinkedHashMap<>();
        erro.put("code", code);
        erro.put("message", getMessage());
        erro.put("pastoral_message", pastoralMessage);
        extra.forEach(erro::putIfAbsent); // extra nunca sobrescreve as chaves canonicas
        return Map.of("error", erro);
    }
}
