package demo;

public final class Suppressed {

    @SuppressJcheck(code = "J0302", reason = "hash fold: truncation is the intended 32-bit wrap")
    static int fold(long hash) {
        return (int) hash;
    }

    @SuppressJcheck(code = "J0706", reason = "kept reassignable for the generated builder chain")
    static String label() {
        String text = "x";
        return text;
    }
}
