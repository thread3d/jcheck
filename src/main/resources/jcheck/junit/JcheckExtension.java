package jcheck.junit;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * jcheck's JUnit 5 extension: a per-test timeout with a thread dump, thread and file-descriptor
 * leak detection, and uncaught-exception capture.
 *
 * <p>This source is compiled by jcheck against the project's own JUnit Platform at test time and
 * registered through JUnit's extension autodetection, so jcheck needs no JUnit dependency of its
 * own and the extension always matches the platform version the project runs.
 *
 * <p>Configuration is by JUnit configuration parameters, which the launcher passes as system
 * properties: {@code jcheck.junit.timeout.seconds}, {@code jcheck.junit.thread_leaks},
 * {@code jcheck.junit.fd_leaks} and {@code jcheck.junit.uncaught}. A zero or negative timeout
 * disables the timeout.
 */
public final class JcheckExtension implements InvocationInterceptor {

    private static final Set<String> ALLOWED_THREADS = Set.of("main", "jcheck-test-watchdog");

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        long timeoutSeconds = longParam(extensionContext, "jcheck.junit.timeout.seconds", 10);
        boolean checkThreads = boolParam(extensionContext, "jcheck.junit.thread_leaks", true);
        boolean checkFds = boolParam(extensionContext, "jcheck.junit.fd_leaks", true);
        boolean captureUncaught = boolParam(extensionContext, "jcheck.junit.uncaught", true);

        Set<String> threadsBefore = liveThreadNames();
        int fdsBefore = fdCount();

        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (captureUncaught) {
            Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> uncaught.compareAndSet(null, error));
        }

        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicReference<String> dump = new AtomicReference<>("");
        Thread testThread = Thread.currentThread();
        Thread watchdog = null;
        if (timeoutSeconds > 0) {
            watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeoutSeconds * 1000L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                timedOut.set(true);
                dump.set(summarize(testThread));
                System.err.println("jcheck: test timed out after " + timeoutSeconds
                    + "s; full thread dump to stderr");
                System.err.println(threadDump());
                testThread.interrupt();
            }, "jcheck-test-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        Throwable thrown = null;
        try {
            invocation.proceed();
        } catch (Throwable t) {
            thrown = t;
        } finally {
            if (watchdog != null) {
                watchdog.interrupt();
            }
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        if (timedOut.get()) {
            throw new AssertionError("test timed out after " + timeoutSeconds + "s\n" + dump.get());
        }
        if (thrown != null) {
            throw thrown;
        }
        Throwable background = uncaught.get();
        if (background != null) {
            throw new AssertionError("uncaught exception in another thread: " + background, background);
        }
        if (checkThreads) {
            List<String> leaked = new ArrayList<>();
            for (String name : liveThreadNames()) {
                if (!threadsBefore.contains(name) && !ALLOWED_THREADS.contains(name)
                    && !name.startsWith("junit") && !name.startsWith("ForkJoinPool")
                    && !name.startsWith("Common-Cleaner")) {
                    leaked.add(name);
                }
            }
            if (!leaked.isEmpty()) {
                throw new AssertionError("thread leak detected: " + leaked);
            }
        }
        if (checkFds && fdsBefore >= 0) {
            int after = fdCount();
            if (after > fdsBefore) {
                throw new AssertionError("file-descriptor leak detected: "
                    + fdsBefore + " -> " + after);
            }
        }
    }

    private static Set<String> liveThreadNames() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive()) {
                names.add(thread.getName());
            }
        }
        return names;
    }

    private static int fdCount() {
        for (String dir : new String[] {"/proc/self/fd", "/dev/fd"}) {
            Path path = Path.of(dir);
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.list(path)) {
                    return (int) stream.count();
                } catch (Exception ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String threadDump() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        for (ThreadInfo info : bean.dumpAllThreads(true, true)) {
            sb.append('"').append(info.getThreadName()).append('"')
              .append(" state=").append(info.getThreadState()).append('\n');
            for (StackTraceElement frame : info.getStackTrace()) {
                sb.append("    at ").append(frame).append('\n');
            }
        }
        long[] deadlocked = bean.findDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            sb.append("deadlock detected involving ").append(deadlocked.length).append(" threads\n");
            for (ThreadInfo info : bean.getThreadInfo(deadlocked, true, true)) {
                if (info != null) {
                    sb.append("  ").append(info.getThreadName()).append(" waits on ")
                      .append(info.getLockName()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * The short form that goes into the failure message: the test thread's stack and any deadlock,
     * capped so a timed-out test does not bury the rest of the report. The full dump goes to
     * stderr at the same time.
     */
    private static String summarize(Thread testThread) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        ThreadInfo info = bean.getThreadInfo(testThread.getId(), Integer.MAX_VALUE);
        if (info != null) {
            sb.append('"').append(info.getThreadName()).append('"')
              .append(" state=").append(info.getThreadState()).append('\n');
            int shown = 0;
            for (StackTraceElement frame : info.getStackTrace()) {
                if (shown++ >= 20) {
                    sb.append("    ... ").append(info.getStackTrace().length - 20)
                      .append(" more frame(s) on stderr\n");
                    break;
                }
                sb.append("    at ").append(frame).append('\n');
            }
        }
        long[] deadlocked = bean.findDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            sb.append("deadlock detected involving ").append(deadlocked.length).append(" threads\n");
        }
        return sb.toString();
    }

    private static long longParam(ExtensionContext context, String key, long fallback) {
        Optional<String> value = context.getConfigurationParameter(key);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.get().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolParam(ExtensionContext context, String key, boolean fallback) {
        Optional<String> value = context.getConfigurationParameter(key);
        if (value.isEmpty()) {
            return fallback;
        }
        String v = value.get().trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("fail") || v.equals("true") || v.equals("yes")) {
            return true;
        }
        if (v.equals("warn") || v.equals("ignore") || v.equals("false") || v.equals("no")) {
            return false;
        }
        return fallback;
    }
}
