package jcheck.render;

import java.util.List;
import java.util.Locale;

/** Renderer lookup by format name. */
public final class Renderers {

    public static final List<String> FORMATS = List.of("human", "json", "llm", "sarif");

    private Renderers() {
    }

    public static Renderer forFormat(String format) {
        String f = format == null ? "human" : format.trim().toLowerCase(Locale.ROOT);
        switch (f) {
            case "human":
                return new HumanRenderer();
            case "json":
                return new JsonRenderer();
            case "llm":
                return new LlmRenderer();
            case "sarif":
                return new SarifRenderer();
            default:
                throw new IllegalArgumentException(
                    "unknown message format '" + format + "'; expected one of " + String.join("|", FORMATS));
        }
    }
}
