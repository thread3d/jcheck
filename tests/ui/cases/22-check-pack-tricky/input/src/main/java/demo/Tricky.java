package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Tricky-but-safe constructs that the T3 pack must not report: generics, lambdas, try/finally,
 * an inner class, a record, a defensive copy, iteration over one collection while mutating another,
 * and final locals. Run under the strict profile so a stray warning fails the golden.
 */
public final class Tricky {

    private final List<String> items;

    Tricky(List<String> items) {
        this.items = List.copyOf(items);
    }

    List<String> items() {
        return items;
    }

    static <T> List<T> map(List<T> input, Function<? super T, ? extends T> f) {
        final List<T> out = new ArrayList<>(input.size());
        for (T value : input) {
            out.add(f.apply(value));
        }
        return out;
    }

    static int firstLength(List<String> values) {
        try {
            final String first = values.get(0);
            return first.length();
        } catch (IndexOutOfBoundsException e) {
            return 0;
        } finally {
            // nothing to release
        }
    }

    static String labels(List<String> values) {
        final StringBuilder out = new StringBuilder();
        values.forEach(v -> out.append(v));
        return out.toString();
    }

    static final class Inner {
        private int count;

        int bump() {
            return ++count;
        }
    }

    record Pair(int left, int right) {
        int sum() {
            return Math.addExact(left, right);
        }
    }
}
