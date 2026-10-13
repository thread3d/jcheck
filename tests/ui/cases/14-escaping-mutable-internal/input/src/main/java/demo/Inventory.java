package demo;

import java.util.List;

public final class Inventory {

    private final List<String> items;

    Inventory(List<String> items) {
        this.items = items;
    }

    List<String> items() {
        return items;
    }

    List<String> snapshot() {
        return List.copyOf(items);
    }
}
