package io.github.radeklesniewski.dq.example.http;

import io.github.radeklesniewski.dq.ExecutionMode;
import io.github.radeklesniewski.dq.RunOptions;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.select.RuleFilter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates query parameters into run options. Every parameter is optional and filters combine with AND:
 *
 * <pre>
 * status=RELEASED,DRAFT      rule status any of (the engine still runs RELEASED only)
 * country=DE                 rules applicable to DE (WORLD + DE)
 * category=tax,bank          rules with any of these categories
 * mode=PARALLEL_ORDERED      SEQUENTIAL (default) | PARALLEL_ORDERED | PARALLEL_UNORDERED
 * batchSize=500              1..100000 (default 1000)
 * emitOutOfScope=true        NOT_APPLICABLE results for out-of-scope rules
 * </pre>
 *
 * @param options the run options
 */
record RunRequest(RunOptions options) {

    private static final Set<String> KNOWN = Set.of(
            "status", "country", "category", "mode", "batchSize", "emitOutOfScope", "format");
    static final int MAX_BATCH_SIZE = 100_000;

    /** Thrown for invalid parameters; the message is safe to return to the client. */
    static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message);
        }
    }

    /**
     * Parses the raw query string of a request.
     *
     * @throws BadRequest if any parameter is unknown or invalid
     */
    static RunRequest parse(String rawQuery) {
        Map<String, String> params = params(rawQuery);
        RunOptions.Builder options = RunOptions.builder().ruleFilter(filter(params));
        try {
            if (params.containsKey("mode")) {
                options.executionMode(ExecutionMode.valueOf(params.get("mode")));
                // Shared server: don't let one request take more than the machine's cores.
                options.parallelism(Runtime.getRuntime().availableProcessors());
            }
            if (params.containsKey("batchSize")) {
                int batchSize = Integer.parseInt(params.get("batchSize"));
                if (batchSize > MAX_BATCH_SIZE) {
                    throw new BadRequest("batchSize must be at most " + MAX_BATCH_SIZE);
                }
                options.batchSize(batchSize);
            }
            if (params.containsKey("emitOutOfScope")) {
                options.emitOutOfScope(bool("emitOutOfScope", params.get("emitOutOfScope")));
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e instanceof NumberFormatException
                    ? "batchSize must be a number"
                    : e.getMessage().startsWith("No enum constant")
                            ? "mode must be one of " + Arrays.toString(ExecutionMode.values())
                            : e.getMessage());
        }
        return new RunRequest(options.build());
    }

    /** Builds the rule filter from the {@code status}, {@code country} and {@code category} parameters. */
    static RuleFilter filter(Map<String, String> params) {
        List<RuleFilter> filters = new ArrayList<>();
        try {
            if (params.containsKey("status")) {
                filters.add(RuleFilter.status(list(params.get("status")).stream()
                        .map(RuleStatus::valueOf).toArray(RuleStatus[]::new)));
            }
            if (params.containsKey("country")) {
                filters.add(RuleFilter.country(params.get("country")));
            }
            if (params.containsKey("category")) {
                filters.add(RuleFilter.category(list(params.get("category")).toArray(String[]::new)));
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e.getMessage().startsWith("No enum constant")
                    ? "status must be any of " + Arrays.toString(RuleStatus.values())
                    : e.getMessage());
        }
        return filters.isEmpty() ? RuleFilter.all() : RuleFilter.allOf(filters.toArray(RuleFilter[]::new));
    }

    /** Decodes a query string, rejecting unknown or repeated parameters. */
    static Map<String, String> params(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (!KNOWN.contains(name)) {
                throw new BadRequest("Unknown parameter '" + name + "'; known: " + KNOWN.stream().sorted().toList());
            }
            if (params.put(name, value) != null) {
                throw new BadRequest("Parameter '" + name + "' given more than once; use a comma-separated list");
            }
        }
        return params;
    }

    private static List<String> list(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean bool(String name, String value) {
        return switch (value) {
            case "true", "" -> true;
            case "false" -> false;
            default -> throw new BadRequest(name + " must be true or false");
        };
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequest("Malformed query string");
        }
    }
}
