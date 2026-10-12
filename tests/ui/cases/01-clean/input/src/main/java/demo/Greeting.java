package demo;

public final class Greeting {
    private Greeting() {
    }

    public static String greet(final String name) {
        final String prefix = "hello";
        return prefix + " " + name;
    }
}
