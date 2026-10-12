package jcheck.model;

/** Diagnostic severity, mirroring rustc's model. */
public enum Level {
    ERROR("error", 'E'),
    WARNING("warning", 'W'),
    NOTE("note", 'N'),
    HELP("help", 'H');

    private final String wire;
    private final char shortName;

    Level(String wire, char shortName) {
        this.wire = wire;
        this.shortName = shortName;
    }

    /** The lowercase name used on the wire and in rendered output. */
    public String wire() {
        return wire;
    }

    public char shortName() {
        return shortName;
    }

    /** Parses a config/CLI level name; returns null when unknown. */
    public static Level parse(String s) {
        if (s == null) {
            return null;
        }
        switch (s.trim().toLowerCase()) {
            case "error":
            case "deny":
                return ERROR;
            case "warning":
            case "warn":
                return WARNING;
            case "note":
                return NOTE;
            case "help":
                return HELP;
            case "allow":
                return null;
            default:
                return null;
        }
    }
}
