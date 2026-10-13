package jcheck.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Suppresses one jcheck code, with a reason.
 *
 * <p>This is the supported suppression form. A blanket "disable all" is deliberately not
 * available: {@code code = "all"} suppresses nothing. The reason is required and is itself checked
 * as J0003.
 *
 * <pre>{@code
 * @SuppressJcheck(code = "J0302", reason = "hash fold: truncation is the intended 32-bit wrap")
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.LOCAL_VARIABLE,
    ElementType.CONSTRUCTOR, ElementType.PACKAGE})
public @interface SuppressJcheck {
    /** The jcheck code this suppression applies to, for example {@code "J0801"}. */
    String code();

    /** Why the diagnostic is inapplicable here. Required; J0003 rejects a blank one. */
    String reason();
}
