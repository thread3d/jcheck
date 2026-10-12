package jcheck.daemon;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import jcheck.cli.CheckRequest;

/** Thin client for the jcheck daemon. */
public final class DaemonClient {

    public static final class Response {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Response(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private DaemonClient() {
    }

    public static boolean isRunning(String root) {
        return ping(root);
    }

    /** True when a daemon for this root answers. Never throws. */
    public static boolean ping(String root) {
        try {
            Response r = simple(Protocol.OP_PING, root);
            return r.exitCode == 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static boolean shutdown(String root) {
        try {
            Response r = simple(Protocol.OP_SHUTDOWN, root);
            return r.exitCode == 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static Response check(String root, CheckRequest req) throws IOException {
        try (SocketChannel ch = connect(root)) {
            DataOutputStream out = new DataOutputStream(Channels.newOutputStream(ch));
            out.writeInt(Protocol.MAGIC);
            out.writeInt(Protocol.OP_CHECK);
            out.writeUTF(req.root == null ? "" : req.root);
            out.writeUTF(req.format == null ? "human" : req.format);
            out.writeInt(req.maxDiagnostics);
            out.writeInt(req.contextLines);
            out.writeUTF(req.profile == null ? "" : req.profile);
            out.writeUTF(join(req.deny));
            out.writeUTF(join(req.warn));
            out.writeUTF(join(req.allow));
            out.writeBoolean(req.denyWarnings);
            out.writeBoolean(req.reportSuppressions);
            out.writeInt(req.release == null ? 0 : req.release);
            out.writeUTF(joinPaths(req.sources));
            out.writeUTF(joinPaths(req.classpath));
            out.flush();
            return readResponse(new DataInputStream(Channels.newInputStream(ch)));
        }
    }

    private static Response simple(int op, String root) throws IOException {
        try (SocketChannel ch = connect(root)) {
            DataOutputStream out = new DataOutputStream(Channels.newOutputStream(ch));
            out.writeInt(Protocol.MAGIC);
            out.writeInt(op);
            out.flush();
            return readResponse(new DataInputStream(Channels.newInputStream(ch)));
        }
    }

    private static SocketChannel connect(String root) throws IOException {
        Path socket = Daemon.socketPath(root);
        if (!Files.exists(socket)) {
            throw new IOException("no daemon socket at " + socket);
        }
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            ch.connect(UnixDomainSocketAddress.of(socket));
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        return ch;
    }

    private static Response readResponse(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != Protocol.MAGIC) {
            throw new IOException("daemon protocol mismatch");
        }
        int exit = in.readInt();
        byte[] outBytes = new byte[in.readInt()];
        in.readFully(outBytes);
        byte[] errBytes = new byte[in.readInt()];
        in.readFully(errBytes);
        return new Response(exit,
            new String(outBytes, StandardCharsets.UTF_8),
            new String(errBytes, StandardCharsets.UTF_8));
    }

    private static String join(java.util.List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join("\u0001", values);
    }

    private static String joinPaths(java.util.List<Path> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append('\u0001');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
