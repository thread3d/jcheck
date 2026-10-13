package demo;

public final class Suppressions {

    @SuppressWarnings("NullAway")
    void bare() {
    }

    @Unsafe(reason = "")
    void blankUnsafe() {
    }

    @SuppressJcheck(code = "J0302", reason = "")
    void blankJcheck() {
    }

    @SuppressJcheck(code = "J0302", reason = "hash fold: the 32-bit wrap is the intended checksum")
    void documented() {
    }
}
