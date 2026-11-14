package io.github.radeklesniewski.dq.example.http;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The command line of {@link ExampleHttpServer#main}: {@code [--port N] [--catalog file.json] [--max-body-mb N]}.
 *
 * @param port where to listen; 0 picks a free port
 * @param catalog a catalog file to watch, or empty for the bundled example catalog
 * @param maxBodyBytes larger request bodies are rejected with {@code 413}
 */
record ServerArguments(int port, Optional<Path> catalog, long maxBodyBytes) {

    static final int DEFAULT_PORT = 8080;
    private static final long MIB = 1024L * 1024;

    /**
     * Parses the command line.
     *
     * @throws IllegalArgumentException naming the argument that's unknown, lacks a value or has an invalid one
     */
    static ServerArguments parse(String... args) {
        int port = DEFAULT_PORT;
        Path catalog = null;
        long maxBodyBytes = ExampleHttpServer.DEFAULT_MAX_BODY_BYTES;
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i];
            if (!name.equals("--port") && !name.equals("--catalog") && !name.equals("--max-body-mb")) {
                throw new IllegalArgumentException(
                        "Unknown argument '" + name + "'; expected --port, --catalog or --max-body-mb");
            }
            if (i + 1 == args.length) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            String value = args[i + 1];
            switch (name) {
                case "--port" -> port = (int) number(name, value, 0, 65_535);
                case "--catalog" -> catalog = Path.of(value);
                default -> maxBodyBytes = number(name, value, 1, Long.MAX_VALUE / MIB) * MIB;
            }
        }
        return new ServerArguments(port, Optional.ofNullable(catalog), maxBodyBytes);
    }

    private static long number(String name, String value, long min, long max) {
        long number;
        try {
            number = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number, got '" + value + "'", e);
        }
        if (number < min || number > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ", got " + number);
        }
        return number;
    }
}
