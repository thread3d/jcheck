/**
 * The test source root a verifier can see.
 *
 * <p>jcheck's own suite is {@code tests/run.sh}: a hand-written harness that needs only a JDK and
 * deliberately lives outside Maven's layout, because jcheck ships no third-party dependency. An
 * external verifier (CodeWitness) can only compile the tests it generates where a build tool's test
 * root exists, so this empty package exists to give it one. The tests written here during a run are
 * temporary, belong to the verifier, and are removed when it finishes.
 *
 * <p>Adding real tests to this tree would be a second test story for the same code. Keep jcheck's
 * assertions in {@code tests/java}, and keep this package free of them.
 */
package jcheck.verify;
