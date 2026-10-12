package demo;

import java.util.List;

public final class Cart {
    public static int total(List<Integer> parts) {
        String label = "total";
        int sum = 0;
        for (Integer part : parts) {
            sum += part;
        }
        return sum + label.length();
    }
}
