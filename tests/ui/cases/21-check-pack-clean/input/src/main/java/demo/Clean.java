package demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Clean {

    private final List<String> items;

    Clean(List<String> items) {
        this.items = List.copyOf(items);
    }

    List<String> items() {
        return items;
    }

    static int total(List<Integer> parts) {
        final int[] sum = {0};
        for (Integer part : parts) {
            sum[0] = Math.addExact(sum[0], part);
        }
        return sum[0];
    }

    static String read(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return reader.readLine();
        }
    }

    static void jobs(List<Runnable> jobs) {
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            jobs.forEach(pool::submit);
        } finally {
            pool.shutdown();
        }
    }
}
