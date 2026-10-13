package demo;

import static jcheck.support.Todo.Todo;

public final class Plan {

    int fee(String plan) {
        if ("free".equals(plan)) {
            return 0;
        }
        return Todo();
    }
}
