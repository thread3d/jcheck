package jcheck.daemon;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import jcheck.cli.CheckCommand;
import jcheck.cli.CheckRequest;

/**
 * A long-lived JVM that keeps javac and the analyzers warm and serves check requests over a
 * Unix domain socket. The CLI is a thin client.
 *
 * <p>The socket and pidfile live in the system temp directory, keyed by a hash of the project
 * root, so serving a project never writes into that project.
 */
public final class Daemon {

    private Daemon() {
    }

    public static Path runtimeDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "jcheck");
    }

    /** Canonical form of a project root, so client and daemon always agree on the key. */
    public static String norm(String root) {
        return Path.of(root == null ? "." : root).toAbsolutePath().normalize().toString();
    }

    public static String key(String root) {
        String canonical = norm(root);
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < canonical.length(); i++) {
            hash ^= canonical.charAt(i);
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }

    public static Path socketPath(String root) {
        return runtimeDir().resolve("jcheck-" + key(root) + ".sock");
    }

    public static Path pidPath(String root) {
        return runtimeDir().resolve("jcheck-" + key(root) + ".pid");
    }

    public static Path logPath(String root) {
        return runtimeDir().resolve("jcheck-" + key(root) + ".log");
    }

    /** Runs the accept loop. Blocks until {@link Protocol#OP_SHUTDOWN} or an unrecoverable error. */
    public static void serve(String rootStr) throws IOException {
        Path root = Path.of(rootStr).toAbsolutePath().normalize();
        String rootPath = root.toString();
        Files.createDirectories(runtimeDir());
        Path socket = socketPath(rootPath);
        Path pid = pidPath(rootPath);
        Files.deleteIfExists(socket);

        boolean[] running = {true};
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.writeString(pid, String.valueOf(ProcessHandle.current().pid()),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(socket);
                    Files.deleteIfExists(pid);
                } catch (IOException ignored) {
                    // best effort
                }
            }));

            while (running[0]) {
                try (SocketChannel ch = server.accept()) {
                    running[0] = handle(ch, rootPath);
                } catch (IOException e) {
                    if (!running[0]) {
                        break;
                    }
                    // a dropped connection must not kill the daemon
                }
            }
        } finally {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(pid);
        }
    }

    /** @return false when the client asked the daemon to shut down */
    private static boolean handle(SocketChannel ch, String root) throws IOException {
        DataInputStream in = new DataInputStream(Channels.newInputStream(ch));
        DataOutputStream out = new DataOutputStream(Channels.newOutputStream(ch));
        int magic = in.readInt();
        if (magic != Protocol.MAGIC) {
            writeResponse(out, 2, "", "jcheck: protocol mismatch\n");
            return true;
        }
        int op = in.readInt();
        switch (op) {
            case Protocol.OP_PING:
                writeResponse(out, 0, "pong\n", "");
                return true;
            case Protocol.OP_SHUTDOWN:
                writeResponse(out, 0, "stopping\n", "");
                return false;
            case Protocol.OP_CHECK:
                CheckRequest req = readRequest(in, root);
                CheckCommand.Outcome outcome = CheckCommand.run(req);
                writeResponse(out, outcome.exitCode, outcome.stdout, outcome.stderr);
                return true;
            default:
                writeResponse(out, 2, "", "jcheck: unknown daemon opcode " + op + "\n");
                return true;
        }
    }

    private static CheckRequest readRequest(DataInputStream in, String defaultRoot) throws IOException {
        CheckRequest req = new CheckRequest();
        String root = in.readUTF();
        req.root = root.isEmpty() ? defaultRoot : root;
        req.format = in.readUTF();
        req.maxDiagnostics = in.readInt();
        req.contextLines = in.readInt();
        String profile = in.readUTF();
        req.profile = profile.isEmpty() ? null : profile;
        req.deny = split(in.readUTF());
        req.warn = split(in.readUTF());
        req.allow = split(in.readUTF());
        req.denyWarnings = in.readBoolean();
        req.reportSuppressions = in.readBoolean();
        int release = in.readInt();
        req.release = release <= 0 ? null : release;
        List<Path> sources = new ArrayList<>();
        for (String s : split(in.readUTF())) {
            sources.add(Path.of(s));
        }
        req.sources = sources;
        List<Path> classpath = new ArrayList<>();
        for (String s : split(in.readUTF())) {
            classpath.add(Path.of(s));
        }
        req.classpath = classpath;
        return req;
    }

    static void writeResponse(DataOutputStream out, int exitCode, String stdout, String stderr)
            throws IOException {
        byte[] outBytes = stdout.getBytes(StandardCharsets.UTF_8);
        byte[] errBytes = stderr.getBytes(StandardCharsets.UTF_8);
        out.writeInt(Protocol.MAGIC);
        out.writeInt(exitCode);
        out.writeInt(outBytes.length);
        out.write(outBytes);
        out.writeInt(errBytes.length);
        out.write(errBytes);
        out.flush();
    }

    static List<String> split(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null || joined.isEmpty()) {
            return out;
        }
        for (String s : joined.split("\u0001", -1)) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    static String join(List<String> values) {
        return String.join("\u0001", values);
    }

    /** Spawns a detached daemon JVM and waits for its socket to accept connections. */
    public static boolean spawn(Path root, long timeoutMillis) throws IOException {
        String rootPath = root.toAbsolutePath().normalize().toString();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = System.getProperty("java.class.path");
        Files.createDirectories(runtimeDir());
        Path log = logPath(rootPath);
        ProcessBuilder pb = new ProcessBuilder(
            java.toString(), "-cp", classpath, "jcheck.cli.Main",
            "daemon", "run", "--root", rootPath);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        pb.start();

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (DaemonClient.ping(rootPath)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Writes a small marker so a caller can stream a message to a log without a full logger. */
    public static void log(Path logFile, String message) {
        try (OutputStream os = Files.newOutputStream(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            os.write((message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // logging must never fail a run
        }
    }
}
