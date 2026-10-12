package jcheck.model;

/** A single text replacement over a source span. */
public record Edit(Span span, String replacement) {
}
