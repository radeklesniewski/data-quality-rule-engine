package io.github.radeklesniewski.dq.bench;

import io.github.radeklesniewski.dq.ExecutionMode;
import io.github.radeklesniewski.dq.RunOptions;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.ValidationEngine;
import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Engine throughput in records per second, by execution mode, catalog size and batch size.
 *
 * <p>Records are pre-built, so the numbers measure the engine (selection, isolation, provenance tracking, mapping,
 * emission) rather than input parsing. Run with {@code ./gradlew :engine-core:jmh}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class EngineBenchmark {

    private static final int RECORDS = 100_000;

    @Param({"SEQUENTIAL", "PARALLEL_ORDERED", "PARALLEL_UNORDERED"})
    public ExecutionMode mode;

    /** "fixture": the 3 cheap rules of the brief. "realistic": 30 rules incl. regexes and an IBAN mod-97 check. */
    @Param({"fixture", "realistic"})
    public String catalog;

    @Param({"100", "1000"})
    public int batchSize;

    private List<DataRecord> records;
    private RuleCatalog rules;
    private RunOptions options;
    private final ValidationEngine engine = ValidationEngine.create();

    @Setup
    public void setUp() {
        records = IntStream.range(0, RECORDS).mapToObj(EngineBenchmark::record).toList();
        rules = "fixture".equals(catalog) ? RuleCatalog.of(fixtureRules()) : RuleCatalog.of(realisticRules());
        options = RunOptions.builder().executionMode(mode).batchSize(batchSize).build();
    }

    @Benchmark
    @OperationsPerInvocation(RECORDS)
    public RunSummary validate(Blackhole blackhole) {
        return engine.run(RecordSource.of(records), rules, options, blackhole::consume);
    }

    // ------------------------------------------------------------------ data

    private static DataRecord record(int i) {
        String country = List.of("DE", "FR", "PL", "ZZ").get(i % 4);
        return DataRecord.ofMap(Map.of(
                "id", "r" + i,
                "country", country,
                "vatId", country + String.format("%09d", i),
                "legalName", "Company " + i + (i % 2 == 0 ? " GmbH" : " SARL"),
                "iban", country + String.format("%02d", i % 97) + "3704004405320130" + String.format("%02d", i % 100),
                "address", Map.of("city", "City " + (i % 50), "postalCode", String.format("%05d", i % 99_999))));
    }

    private static Rule.Builder released(String id) {
        return Rule.builder(id).label(id).status(RuleStatus.RELEASED).severity(Severity.ERROR)
                .decisionMapping(DecisionMapping.builder()
                        .map("ok", Decision.VALID).map("bad", Decision.INVALID).orElse(Decision.REVIEW));
    }

    private static List<Rule> fixtureRules() {
        return List.of(
                released("countryBlocked")
                        .logic(r -> r.getString("country").filter("ZZ"::equals).isPresent() ? "blocked" : "ok")
                        .decisionMapping(DecisionMapping.builder().map("blocked", Decision.INVALID)
                                .orElse(Decision.NOT_APPLICABLE))
                        .build(),
                released("vatFormat")
                        .logic(r -> r.getString("vatId").filter(v -> v.length() >= 9).isPresent() ? "ok" : "bad")
                        .build(),
                released("ibanFormat")
                        .logic(r -> {
                            Optional<String> country = r.getString("country");
                            Optional<String> iban = r.getString("iban");
                            return country.isPresent() && iban.filter(v -> v.startsWith(country.get())).isPresent()
                                    ? "ok" : "bad";
                        })
                        .build());
    }

    private static List<Rule> realisticRules() {
        List<Rule> rules = new ArrayList<>(fixtureRules());
        rules.add(released("ibanChecksum").logic(r -> r.getString("iban").filter(EngineBenchmark::mod97).isPresent()
                ? "ok" : "bad").build());
        String[] fields = {"vatId", "legalName", "iban", "address.city", "address.postalCode"};
        String[] patterns = {"^[A-Z]{2}[0-9]{9}$", "^[A-Za-z0-9 .&-]{3,80}$", "^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$",
                "^[\\p{L} .'-]{2,60}$", "^[0-9]{5}$"};
        for (int i = 0; rules.size() < 30; i++) {
            String field = fields[i % fields.length];
            Pattern pattern = Pattern.compile(patterns[i % patterns.length]);
            rules.add(released("regex" + i)
                    .logic(r -> r.getString(field).filter(v -> pattern.matcher(v).matches()).isPresent() ? "ok" : "bad")
                    .build());
        }
        return rules;
    }

    private static boolean mod97(String iban) {
        if (iban.length() < 5) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : (iban.substring(4) + iban.substring(0, 4)).toCharArray()) {
            digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
