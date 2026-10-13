package jcheck.support;

/**
 * The marker helpers jcheck-support ships. A call raises J0902 so an unfinished branch cannot
 * ship silently; at runtime the message carries the caller's file and line.
 */
public final class Todo {

    private Todo() {
    }

    public static <T> T Todo() {
        throw new UnsupportedOperationException("unfinished marker at " + caller());
    }

    public static <T> T Unreachable() {
        throw new AssertionError("unreachable branch at " + caller());
    }

    public static <T> T Unimplemented() {
        throw new UnsupportedOperationException("unimplemented at " + caller());
    }

    private static String caller() {
        return StackWalker.getInstance()
            .walk(frames -> frames.skip(2).findFirst()
                .map(f -> f.getClassName() + ":" + f.getLineNumber())
                .orElse("unknown"));
    }
}
