package jcheck.render;

import jcheck.model.CheckReport;

/** Renders a {@link CheckReport} in one output format. */
public interface Renderer {

    /** Stable format name: human | json | llm | sarif. */
    String format();

    /** Returns the complete text to print, including any trailing newline. */
    String render(CheckReport report, RenderOptions options);
}
