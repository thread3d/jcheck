package jcheck.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or class as an explicit unsafe region, the way {@code unsafe} does in Rust.
 *
 * <p>J0701 reports {@code sun.misc.Unsafe}, {@code jdk.internal.*}, {@code setAccessible}, JNI
 * loads and native methods, FFM downcalls and {@code Thread.stop} outside such a region. The
 * reason must be non-empty — it is checked as J0003 — so every exception is greppable and
 * reviewable.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Unsafe {
    /** Why the unsafe operation is necessary, in enough detail to review. */
    String reason();
}
