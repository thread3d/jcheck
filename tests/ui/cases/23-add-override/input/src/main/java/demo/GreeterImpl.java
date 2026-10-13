package demo;

interface Greeter {
    String greet(String name);
}

public final class GreeterImpl implements Greeter {

    public String greet(String name) {
        return "hi " + name;
    }

    @Override
    public String toString() {
        return "greeter";
    }
}
