package io.github.radeklesniewski.dq.example.http;

import io.github.radeklesniewski.dq.json.outcome.JsonOutcomeWriter;
import io.github.radeklesniewski.dq.json.record.JsonRecordSources;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The two wire formats, for request bodies and responses alike, and how a request chooses them: {@code Content-Type}
 * for the body, {@code ?format=} or {@code Accept} for the response.
 */
enum Format {

    /** One JSON array of records in; one {@code {"outcomes":[...],"summary":{...}}} document out. */
    JSON("application/json") {
        @Override
        RecordSource reader(InputStream in) {
            return JsonRecordSources.array(in);
        }

        @Override
        JsonOutcomeWriter writer(OutputStream out) {
            return JsonOutcomeWriter.document(out);
        }
    },

    /** One record per line in; one outcome per line out, then a summary line. */
    NDJSON("application/x-ndjson") {
        @Override
        RecordSource reader(InputStream in) {
            return JsonRecordSources.lines(in);
        }

        @Override
        JsonOutcomeWriter writer(OutputStream out) {
            return JsonOutcomeWriter.lines(out);
        }
    };

    private final String mediaType;

    Format(String mediaType) {
        this.mediaType = mediaType;
    }

    String mediaType() {
        return mediaType;
    }

    /** Reads records in this format; the source owns {@code in}. */
    abstract RecordSource reader(InputStream in);

    /** Writes outcomes in this format; the writer owns {@code out}. */
    abstract JsonOutcomeWriter writer(OutputStream out);

    /**
     * The request body's format. A missing {@code Content-Type} means JSON.
     *
     * @param header the header value, or {@code null} if absent
     * @throws HttpError {@code 415} for any other media type
     */
    static Format ofContentType(String header) {
        if (header == null || header.isBlank()) {
            return JSON;
        }
        return byMediaType(mediaTypeOf(header)).orElseThrow(() -> HttpError.unsupportedMediaType(
                "Content-Type must be " + JSON.mediaType + " or " + NDJSON.mediaType));
    }

    /**
     * The response format named by the {@code format} query parameter, which wins over {@code Accept}.
     *
     * @throws HttpError {@code 400} unless it's {@code json} or {@code ndjson} (any case)
     */
    static Format ofParameter(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "json" -> JSON;
            case "ndjson" -> NDJSON;
            default -> throw HttpError.badRequest("format must be json or ndjson");
        };
    }

    /**
     * The response format negotiated from an {@code Accept} header such as
     * {@code application/x-ndjson, application/json;q=0.9}: the supported type with the highest quality, the first one
     * listed on a tie. {@code *}{@code /*} and {@code application/*} mean JSON, and {@code q=0} means "not this one".
     * Without a header, or if nothing listed is supported, the answer is JSON, the server's default.
     *
     * @param header the header value, or {@code null} if absent
     */
    static Format negotiate(String header) {
        if (header == null || header.isBlank()) {
            return JSON;
        }
        Format best = JSON;
        double bestQuality = 0;
        for (String range : header.split(",")) {
            String[] parts = range.split(";");
            Optional<Format> format = acceptable(parts[0].trim().toLowerCase(Locale.ROOT));
            double quality = quality(parts);
            if (format.isPresent() && quality > bestQuality) {
                best = format.get();
                bestQuality = quality;
            }
        }
        return best;
    }

    private static Optional<Format> acceptable(String mediaRange) {
        return switch (mediaRange) {
            case "*/*", "application/*" -> Optional.of(JSON);
            default -> byMediaType(mediaRange);
        };
    }

    /** The range's {@code q} parameter: 1 if absent, 0 (not acceptable) if malformed. */
    private static double quality(String[] rangeParts) {
        for (int i = 1; i < rangeParts.length; i++) {
            String parameter = rangeParts[i].trim();
            if (parameter.startsWith("q=")) {
                try {
                    return Double.parseDouble(parameter.substring(2));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 1;
    }

    private static Optional<Format> byMediaType(String mediaType) {
        return Arrays.stream(values()).filter(format -> format.mediaType.equals(mediaType)).findFirst();
    }

    /** {@code "Application/JSON; charset=utf-8"} → {@code "application/json"}. */
    private static String mediaTypeOf(String header) {
        return header.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }
}
