package demo;

import java.util.HashMap;
import java.util.Map;

public final class Registry {

    static Map<String, String> services = new HashMap<>();
    private static final Map<String, String> cache = new HashMap<>();
    static final int limit = 10;
    static final String name = "registry";

    static void register(String key, String service) {
        services.put(key, service);
        cache.put(key, service);
    }
}
