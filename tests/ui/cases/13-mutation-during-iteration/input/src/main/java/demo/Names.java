package demo;

import java.util.List;

public final class Names {

    static void dropBlanks(List<String> names) {
        for (String name : names) {
            if (name.isBlank()) {
                names.remove(name);
            }
        }
    }

    static void readOnly(List<String> names) {
        for (String name : names) {
            System.out.println(name);
        }
    }

    static void mutateOther(List<String> names, List<String> other) {
        for (String name : names) {
            other.add(name);
        }
    }

    static void safeCopy(List<String> names) {
        for (String name : List.copyOf(names)) {
            names.remove(name);
        }
    }
}
