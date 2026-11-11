package me.clama.infra.validacao;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Recusa dominios de e-mail temporario (lista em disposable_email_domains.txt). Nulo e vazio passam. */
@Documented
@Constraint(validatedBy = EmailNaoDescartavelValidator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
public @interface EmailNaoDescartavel {
	String message() default "Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
