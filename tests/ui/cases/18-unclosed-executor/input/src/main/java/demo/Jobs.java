package demo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Jobs {

    static void leak(List<Runnable> jobs) {
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        jobs.forEach(pool::submit);
    }

    static void fixed(List<Runnable> jobs) {
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            jobs.forEach(pool::submit);
        } finally {
            pool.shutdown();
        }
    }

    static void threadLeak() {
        final Thread worker = new Thread(() -> System.out.println("work"));
        worker.start();
    }

    static void threadFixed() throws InterruptedException {
        final Thread worker = new Thread(() -> System.out.println("work"));
        worker.start();
        worker.join();
    }
}
