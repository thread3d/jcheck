package demo;

public final class Widths {

    static int count(long total) {
        return (int) total;
    }

    static short small(int value) {
        return (short) value;
    }

    static int harmlessConstant() {
        return (int) 5L;
    }

    static long widened(int value) {
        return value;
    }
}
