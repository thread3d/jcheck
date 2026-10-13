package demo;

import java.lang.reflect.Method;

public final class Reflection {

    static byte[] leak(String text) throws Exception {
        final Method m = String.class.getDeclaredMethod("value");
        m.setAccessible(true);
        return (byte[]) m.invoke(text);
    }

    @Unsafe(reason = "reads the compact String value for a zero-copy codec; layout verified on JDK 20")
    static byte[] allowed(String text) throws Exception {
        final Method m = String.class.getDeclaredMethod("value");
        m.setAccessible(true);
        return (byte[]) m.invoke(text);
    }
}
