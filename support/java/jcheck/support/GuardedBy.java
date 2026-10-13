package jcheck.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the lock that guards every access to a field.
 *
 * <p>J0601 requires this annotation on shared mutable fields of a {@code @ThreadSafe} class.
 * Verifying that the named lock is actually held is Error Prone's {@code GuardedBy} check, not
 * jcheck's.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface GuardedBy {
    /** The lock expression, for example {@code "this"} or {@code "lock"}. */
    String value();
}
