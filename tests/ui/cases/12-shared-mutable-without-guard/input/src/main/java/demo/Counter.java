package demo;

import java.util.concurrent.atomic.AtomicInteger;

@ThreadSafe
public final class Counter {

    private int count;
    private volatile int visible;
    private final AtomicInteger atomic = new AtomicInteger();

    @GuardedBy("this")
    private int guarded;

    synchronized void increment() {
        count++;
        visible++;
        atomic.incrementAndGet();
        guarded++;
    }
}
