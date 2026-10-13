package jcheck.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a class is safe to use from more than one thread.
 *
 * <p>J0601 requires every mutable instance field of a {@code @ThreadSafe} class to name its lock
 * with {@code @GuardedBy}, be {@code volatile}, or be thread-safe by type.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ThreadSafe {
}
