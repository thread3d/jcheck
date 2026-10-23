package jcheck.util;

/**
 * The one place jcheck's version lives.
 *
 * <p>{@code --version}, the SARIF driver block, the MCP {@code capabilities} resource and the LSP
 * {@code initialize} response all quote it. A second copy is a drift bug waiting to happen: the
 * SARIF renderer used to hard-code {@code 0.1.0}, which no release bump would have found. The spec
 * revision travels next to it but moves independently, because the tool's version changes when its
 * behaviour does and the spec's changes when the document does.
 */
public final class Version {

    /** The tool version, in semver form. */
    public static final String VERSION = "1.4.0";

    /** The specification revision this build implements, quoted by {@code --version}. */
    public static final String SPEC = "0.2";

    private Version() {
    }
}
