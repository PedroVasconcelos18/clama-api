package me.clama.infra.validacao;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** CPF (11) ou CNPJ (14) com digitos verificadores; aceita mascara. Nulo e vazio passam (use @NotBlank para exigir). */
@Documented
@Constraint(validatedBy = CpfOuCnpjValidator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
public @interface CpfOuCnpj {
	String message() default "Confira seu CPF ou CNPJ.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
