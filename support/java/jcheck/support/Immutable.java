package jcheck.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an instance is immutable after construction.
 *
 * <p>J0706, J0601 and J0705 treat an {@code @Immutable} value as needing no defensive copy and no
 * lock. The annotation is a promise; verifying it is Error Prone's {@code Immutable} check.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Immutable {
}
