package demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class Readers {

    static String leak(Path path) throws IOException {
        final BufferedReader reader = Files.newBufferedReader(path);
        return reader.readLine();
    }

    static String fixed(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return reader.readLine();
        }
    }

    static long listing(Path path) throws IOException {
        try (Stream<Path> files = Files.list(path)) {
            return files.count();
        }
    }
}
