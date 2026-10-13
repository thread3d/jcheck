package jcheck.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a class, method or package into J0301 UncheckedArithmetic: raw {@code +}, {@code -} and
 * {@code *} on {@code int} or {@code long} must use {@code Math.addExact} and friends.
 *
 * <p>Suggested for parsers, protocol code, and anything carrying sizes, offsets or indices, where
 * silent wraparound turns into a security or correctness bug.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE})
public @interface Checked {
}
