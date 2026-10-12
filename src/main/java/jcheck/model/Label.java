package jcheck.model;

/** A secondary annotation attached to a span, rendered as an underlined region. */
public record Label(Span span, String text) {
}
