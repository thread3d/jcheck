package jcheck.model;

/** How safe it is to apply a suggested edit. Same meaning as rustc's applicability. */
public enum Applicability {
    MACHINE_APPLICABLE("MachineApplicable"),
    MAYBE_INCORRECT("MaybeIncorrect"),
    HAS_PLACEHOLDERS("HasPlaceholders"),
    UNSPECIFIED("Unspecified");

    private final String wire;

    Applicability(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Applicability parse(String s) {
        if (s == null) {
            return UNSPECIFIED;
        }
        for (Applicability a : values()) {
            if (a.wire.equalsIgnoreCase(s.trim()) || a.name().equalsIgnoreCase(s.trim())) {
                return a;
            }
        }
        return UNSPECIFIED;
    }
}
