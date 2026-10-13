package demo;

@Checked
public final class Frame {

    int nextOffset(int offset, int length) {
        return offset + length;
    }

    long area(long width, long height) {
        return width * height;
    }

    long widened(int a, long b) {
        return a + b;
    }
}

final class Plain {

    int sum(int a, int b) {
        return a + b;
    }
}
