package io.github.radeklesniewski.dq.example.http;

import com.sun.net.httpserver.Headers;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a {@code POST /validate} asks for: run options from the query string, and the body and response formats from
 * the headers. Every parameter is optional and filters combine with AND. Enum and boolean values are case-insensitive.
 *
 * <pre>
 * status=RELEASED,DRAFT      rule status any of (the engine still runs RELEASED only)
 * country=DE                 rules applicable to DE (WORLD + DE)
 * category=tax,bank          rules with any of these categories
 * mode=PARALLEL_ORDERED      SEQUENTIAL (default) | PARALLEL_ORDERED | PARALLEL_UNORDERED
 * batchSize=500              1..100000 (default 1000)
 * emitOutOfScope=true        NOT_APPLICABLE results for out-of-scope rules; a bare emitOutOfScope means true
 * format=ndjson              json | ndjson response; wins over the Accept header
 * </pre>
 *
 * @param options the run options
 * @param input the request body's format, from {@code Content-Type}
 * @param output the response format, from {@code format} or {@code Accept}
 */
record RunRequest(RunOptions options, Format input, Format output) {

    private static final Set<String> KNOWN = Set.of(
            "status", "country", "category", "mode", "batchSize", "emitOutOfScope", "format");
    static final int MAX_BATCH_SIZE = 100_000;

    /**
     * Parses a {@code POST /validate} request.
     *
     * @throws HttpError {@code 400} for an unknown, repeated or invalid parameter; {@code 415} for an unsupported
     *     {@code Content-Type}
     */
    static RunRequest parse(String rawQuery, Headers headers) {
        Map<String, String> params = params(rawQuery);
        RunOptions.Builder options = RunOptions.builder().ruleFilter(filter(params));
        param(params, "mode").ifPresent(mode -> options.executionMode(enumValue("mode", mode, ExecutionMode.class)));
        param(params, "batchSize").ifPresent(size -> options.batchSize(batchSize(size)));
        param(params, "emitOutOfScope").ifPresent(flag -> options.emitOutOfScope(bool("emitOutOfScope", flag)));
        Format input = Format.ofContentType(headers.getFirst("Content-Type"));
        Format output = param(params, "format").map(Format::ofParameter)
                .orElseGet(() -> Format.negotiate(headers.getFirst("Accept")));
        return new RunRequest(options.build(), input, output);
    }

    /**
     * The rule selection of a query string, for {@code GET /rules}.
     *
     * @throws HttpError {@code 400} for an unknown, repeated or invalid parameter
     */
    static RuleFilter ruleFilter(String rawQuery) {
        return filter(params(rawQuery));
    }

    /** Builds the rule filter from the {@code status}, {@code country} and {@code category} parameters. */
    private static RuleFilter filter(Map<String, String> params) {
        List<RuleFilter> filters = new ArrayList<>();
        param(params, "status").ifPresent(statuses -> filters.add(RuleFilter.status(list("status", statuses).stream()
                .map(status -> enumValue("status", status, RuleStatus.class)).toArray(RuleStatus[]::new))));
        param(params, "country").ifPresent(country -> filters.add(countryFilter(country)));
        param(params, "category").ifPresent(categories -> filters.add(
                RuleFilter.category(list("category", categories).toArray(String[]::new))));
        return filters.isEmpty() ? RuleFilter.all() : RuleFilter.allOf(filters.toArray(RuleFilter[]::new));
    }

    /** Decodes a query string, rejecting unknown or repeated parameters. */
    private static Map<String, String> params(String rawQuery) {
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
                throw HttpError.badRequest(
                        "Unknown parameter '" + name + "'; known: " + KNOWN.stream().sorted().toList());
            }
            if (params.put(name, value) != null) {
                throw HttpError.badRequest(
                        "Parameter '" + name + "' given more than once; use a comma-separated list");
            }
        }
        return params;
    }

    // ------------------------------------------------------------------ one parser per value type

    private static Optional<String> param(Map<String, String> params, String name) {
        return Optional.ofNullable(params.get(name));
    }

    private static <E extends Enum<E>> E enumValue(String name, String value, Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> HttpError.badRequest(
                        name + " must be one of " + Arrays.toString(type.getEnumConstants())));
    }

    private static int batchSize(String value) {
        int batchSize;
        try {
            batchSize = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw HttpError.badRequest("batchSize must be a number");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw HttpError.badRequest("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        return batchSize;
    }

    /** Strict: {@code Boolean.parseBoolean} would turn every other string, {@code maybe} included, into false. */
    private static boolean bool(String name, String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "" -> true;
            case "false" -> false;
            default -> throw HttpError.badRequest(name + " must be true or false");
        };
    }

    private static RuleFilter countryFilter(String country) {
        try {
            return RuleFilter.country(country);
        } catch (IllegalArgumentException e) { // the library's message names the expected format, not a value
            throw HttpError.badRequest(e.getMessage());
        }
    }

    /** A comma-separated list with at least one entry. */
    private static List<String> list(String name, String value) {
        List<String> entries = Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (entries.isEmpty()) {
            throw HttpError.badRequest(name + " needs at least one value");
        }
        return entries;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw HttpError.badRequest("Malformed query string");
        }
    }
}
