package jcheck.support;

import java.util.Optional;
import java.util.function.Function;

/**
 * A value that is either a success or an expected failure.
 *
 * <p>The strict profile reserves exceptions for programmer error and genuinely exceptional
 * conditions; an expected failure — a parse that did not match, a lookup that found nothing — is a
 * value. {@code Result} is that value, patterned so the compiler can see every failure point.
 *
 * <pre>{@code
 * Result<Integer, String> parsed = parse(text);
 * return switch (parsed) {
 *     case Result.Ok<Integer, String> ok -> ok.value();
 *     case Result.Err<Integer, String> err -> fallback(err.error());
 * };
 * }</pre>
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    boolean isOk();

    default boolean isErr() {
        return !isOk();
    }

    /** The success value, or throw when this is an error. Use only where failure is impossible. */
    T orElseThrow();

    /** The success value, or {@code fallback} when this is an error. */
    default T orElse(T fallback) {
        return isOk() ? orElseThrow() : fallback;
    }

    <U> Result<U, E> map(Function<? super T, ? extends U> f);

    Optional<T> optional();

    /** A successful result. */
    record Ok<T, E>(T value) implements Result<T, E> {

        @Override
        public boolean isOk() {
            return true;
        }

        @Override
        public T orElseThrow() {
            return value;
        }

        @Override
        public <U> Result<U, E> map(Function<? super T, ? extends U> f) {
            return Result.ok(f.apply(value));
        }

        @Override
        public Optional<T> optional() {
            return Optional.ofNullable(value);
        }
    }

    /** A failed result carrying the reason. */
    record Err<T, E>(E error) implements Result<T, E> {

        @Override
        public boolean isOk() {
            return false;
        }

        @Override
        public T orElseThrow() {
            throw new IllegalStateException("expected a success but was an error: " + error);
        }

        @Override
        public <U> Result<U, E> map(Function<? super T, ? extends U> f) {
            return new Err<>(error);
        }

        @Override
        public Optional<T> optional() {
            return Optional.empty();
        }
    }
}
