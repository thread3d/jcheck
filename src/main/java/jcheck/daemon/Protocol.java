package jcheck.daemon;

/** Wire constants for the daemon's length-prefixed binary protocol. */
public final class Protocol {

    /** "JCHK" — guards against a client connecting to something that is not a jcheck daemon. */
    public static final int MAGIC = 0x4A43484B;

    public static final int OP_CHECK = 1;
    public static final int OP_PING = 2;
    public static final int OP_SHUTDOWN = 3;

    public static final String VERSION = "jcheck/1";

    private Protocol() {
    }
}
