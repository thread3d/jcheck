package jcheck.model;

import java.util.List;

/** A proposed fix: one or more edits plus how far to trust them. */
public record Suggestion(Applicability applicability, String message, List<Edit> edits) {

    public boolean isMachineApplicable() {
        return applicability == Applicability.MACHINE_APPLICABLE;
    }
}
