package io.github.radeklesniewski.dq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.CountryScope;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleLogic;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** ADR-0006: every execution mode yields the same outcomes and summary; only cross-batch order may differ. */
class ExecutionModeTest {

    private static final int RECORDS = 1_000;
    private final ValidationEngine engine = ValidationEngine.create();

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    // ---------------------------------------------------------------- input with every kind of outcome

    private static final Rule EXPLODES_SOMETIMES = rule("explodesSometimes", r -> {
        if (r.requireString("legalName").endsWith("13")) {
            throw new IllegalStateException("bad luck");
        }
        return "ok";
    });
    private static final Rule DE_ONLY = Rule.builder("deOnly").label("de").logic(r -> "ok")
            .decisionMapping(DecisionMapping.builder().map("ok", Decision.VALID).orElse(Decision.REVIEW))
            .status(RuleStatus.RELEASED).severity(Severity.INFO).countryScope(CountryScope.of("DE")).build();

    private static final RuleCatalog CATALOG = RuleCatalog.of(List.of(
            Fixtures.COUNTRY_BLOCKED, Fixtures.VAT_FORMAT, Fixtures.IBAN_FORMAT, EXPLODES_SOMETIMES, DE_ONLY));

    private static Rule rule(String id, RuleLogic logic) {
        return Rule.builder(id).label(id).logic(logic)
                .decisionMapping(DecisionMapping.builder().map("ok", Decision.VALID).orElse(Decision.INVALID))
                .status(RuleStatus.RELEASED).severity(Severity.WARNING).build();
    }

    /** Record i, or an unreadable record, or one without an id, so all outcome kinds appear. */
    private static RecordSource mixedSource(int count) {
        int[] next = {0};
        return () -> {
            int i = next[0]++;
            if (i >= count) {
                return Optional.empty();
            }
            if (i % 97 == 50) {
                throw new RecordReadException("unreadable #" + i, null);
            }
            String country = List.of("DE", "FR", "ZZ").get(i % 3);
            Map<String, Object> fields = new HashMap<>();
            if (i % 7 != 3) {
                fields.put("id", "r" + i);
            }
            fields.put("country", country);
            fields.put("legalName", "Company " + i);
            fields.put("iban", (i % 5 == 0 ? "XX" : country) + "123");
            fields.put("vatId", i % 11 == 5 ? new BigDecimal(i) : country + "1234567" + i);
            return Optional.of(DataRecord.ofMap(fields));
        };
    }

    /** A thread-independent, comparable view of an outcome. */
    private static String describe(Outcome outcome) {
        return switch (outcome) {
            case Result r -> "R|" + r.recordIndex() + "|" + r.ruleId() + "|" + r.decision();
            case RuleError e -> "E|" + e.recordIndex() + "|" + e.ruleId() + "|" + e.errorType();
            case RecordError e -> "X|" + e.recordIndex() + "|" + e.errorType();
        };
    }

    private record Run(List<Outcome> outcomes, RunSummary summary) {
        List<String> described() {
            return outcomes.stream().map(ExecutionModeTest::describe).toList();
        }
    }

    private Run run(RunOptions options) {
        List<Outcome> outcomes = new ArrayList<>();
        RunSummary summary = engine.run(mixedSource(RECORDS), CATALOG, options, outcomes::add);
        return new Run(outcomes, summary);
    }

    private static RunOptions options(ExecutionMode mode, int parallelism, int maxInFlight, int batchSize) {
        return RunOptions.builder().executionMode(mode).parallelism(parallelism)
                .maxInFlightBatches(maxInFlight).batchSize(batchSize).build();
    }

    private static List<Thread> liveWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("dq-engine-run") && t.isAlive())
                .toList();
    }

    /** Workers are joined before {@code run} returns; allow a moment for exiting threads to be reaped. */
    private static void assertNoWorkerThreadsLeft() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!liveWorkers().isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(liveWorkers()).isEmpty();
    }

    // ---------------------------------------------------------------- equivalence

    @Nested
    class Equivalence {

        private final Run sequential = run(RunOptions.builder().batchSize(10).build());

        @Test
        void baselineContainsEveryOutcomeKind() {
            assertThat(sequential.outcomes).hasAtLeastOneElementOfType(Result.class)
                    .hasAtLeastOneElementOfType(RuleError.class)
                    .hasAtLeastOneElementOfType(RecordError.class);
        }

        @ParameterizedTest(name = "parallelism={0}, maxInFlight={1}, batchSize={2}")
        @CsvSource({"1,1,1", "2,1,7", "2,3,10", "4,2,1", "4,8,33", "8,16,1000"})
        void orderedParallelEqualsSequentialExactly(int parallelism, int maxInFlight, int batchSize) {
            Run parallel = run(options(ExecutionMode.PARALLEL_ORDERED, parallelism, maxInFlight, batchSize));

            assertThat(parallel.described()).isEqualTo(sequential.described());
            assertThat(withoutBatches(parallel.summary)).isEqualTo(withoutBatches(sequential.summary));
        }

        @ParameterizedTest(name = "parallelism={0}, maxInFlight={1}, batchSize={2}")
        @CsvSource({"2,3,10", "4,8,7", "8,16,1"})
        void unorderedParallelHasSameOutcomesAndKeepsOrderWithinRecords(int parallelism, int maxInFlight,
                int batchSize) {
            Run parallel = run(options(ExecutionMode.PARALLEL_UNORDERED, parallelism, maxInFlight, batchSize));

            List<Outcome> resorted = new ArrayList<>(parallel.outcomes);
            resorted.sort(Comparator.comparingLong(Outcome::recordIndex)); // stable: keeps within-record order
            assertThat(resorted.stream().map(ExecutionModeTest::describe).toList())
                    .isEqualTo(sequential.described());
            assertThat(withoutBatches(parallel.summary)).isEqualTo(withoutBatches(sequential.summary));
        }

        /** Batch counts legitimately differ with batch size; every other total must match. */
        private static RunSummary withoutBatches(RunSummary s) {
            return new RunSummary(s.rulesSelected(), s.recordsRead(), 0, s.resultCount(), s.ruleErrorCount(),
                    s.recordErrorCount(), s.resultsByDecision(), s.resultsBySeverity());
        }
    }

    // ---------------------------------------------------------------- threading guarantees

    @Nested
    class Threading {

        @ParameterizedTest
        @EnumSource(ExecutionMode.class)
        void sinkIsOnlyCalledFromTheCallingThread(ExecutionMode mode) {
            Thread caller = Thread.currentThread();
            Set<Thread> sinkThreads = ConcurrentHashMap.newKeySet();
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger maxConcurrent = new AtomicInteger();

            engine.run(mixedSource(RECORDS), CATALOG, options(mode, 4, 8, 5), outcome -> {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                sinkThreads.add(Thread.currentThread());
                concurrent.decrementAndGet();
            });

            assertThat(sinkThreads).containsExactly(caller);
            assertThat(maxConcurrent).hasValue(1);
        }

        @Test
        void batchesReallyRunConcurrently() throws InterruptedException {
            // Records 0 and 11 are in different batches and wait for each other: needs two batches at once.
            CyclicBarrier barrier = new CyclicBarrier(2);
            Rule rendezvous = rule("rendezvous", r -> {
                if (r.requireString("legalName").endsWith(" 0") || r.requireString("legalName").endsWith(" 11")) {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("batches did not overlap", e);
                    }
                }
                return "ok";
            });
            List<Outcome> outcomes = new ArrayList<>();

            engine.run(mixedSource(20), RuleCatalog.of(rendezvous), options(ExecutionMode.PARALLEL_ORDERED, 2, 2, 10),
                    outcomes::add);

            assertThat(outcomes).noneMatch(o -> o instanceof RuleError e && e.ruleId().equals("rendezvous"));
            assertNoWorkerThreadsLeft();
        }

        @ParameterizedTest
        @EnumSource(value = ExecutionMode.class, names = {"PARALLEL_ORDERED", "PARALLEL_UNORDERED"})
        void workerThreadsDoNotOutliveTheRun(ExecutionMode mode) throws InterruptedException {
            run(options(mode, 4, 4, 10));

            assertNoWorkerThreadsLeft();
        }

        @ParameterizedTest
        @EnumSource(value = ExecutionMode.class, names = {"PARALLEL_ORDERED", "PARALLEL_UNORDERED"})
        void fatalErrorInWorkerPropagatesAndPoolIsShutDown(ExecutionMode mode) throws InterruptedException {
            Rule oom = rule("oom", r -> {
                if (r.requireString("legalName").equals("Company 42")) {
                    throw new OutOfMemoryError("simulated");
                }
                return "ok";
            });

            assertThatThrownBy(() -> engine.run(mixedSource(RECORDS), RuleCatalog.of(oom), options(mode, 4, 4, 10),
                    outcome -> { }))
                    .isInstanceOf(OutOfMemoryError.class).hasMessage("simulated");
            assertNoWorkerThreadsLeft();
        }

        @ParameterizedTest
        @EnumSource(value = ExecutionMode.class, names = {"PARALLEL_ORDERED", "PARALLEL_UNORDERED"})
        void sinkFailurePropagatesAndPoolIsShutDown(ExecutionMode mode) throws InterruptedException {
            assertThatThrownBy(() -> engine.run(mixedSource(RECORDS), CATALOG, options(mode, 4, 4, 10), outcome -> {
                throw new IllegalStateException("disk full");
            })).hasMessage("disk full");
            assertNoWorkerThreadsLeft();
        }
    }

    // ---------------------------------------------------------------- fatal source failure

    @Nested
    class SourceFailure {

        /** Yields {@code good} records, then breaks for good (e.g. a syntax error in the input). */
        private static RecordSource breaksAfter(int good) {
            int[] next = {0};
            return () -> {
                int i = next[0]++;
                if (i >= good) {
                    throw new IllegalStateException("input broken at record " + i);
                }
                return Optional.of(DataRecord.ofMap(Map.of("id", "r" + i)));
            };
        }

        @ParameterizedTest(name = "{0}, batchSize={1}")
        @CsvSource({"SEQUENTIAL,1", "SEQUENTIAL,7", "SEQUENTIAL,1000",
                "PARALLEL_ORDERED,7", "PARALLEL_ORDERED,1000", "PARALLEL_UNORDERED,7", "PARALLEL_UNORDERED,1000"})
        void everythingReadBeforeTheBreakIsDeliveredThenTheRunFails(ExecutionMode mode, int batchSize)
                throws InterruptedException {
            List<Outcome> delivered = new ArrayList<>();
            Rule ok = rule("ok", r -> "ok");

            assertThatThrownBy(() -> engine.run(breaksAfter(50), RuleCatalog.of(ok), options(mode, 4, 4, batchSize),
                    delivered::add))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("input broken at record 50");
            assertThat(delivered).hasSize(50);
            assertThat(delivered.stream().map(Outcome::recordIndex).sorted().toList())
                    .isEqualTo(java.util.stream.LongStream.range(0, 50).boxed().toList());
            assertNoWorkerThreadsLeft();
        }
    }

    // ---------------------------------------------------------------- cancellation

    @Nested
    class Cancellation {

        @ParameterizedTest
        @EnumSource(ExecutionMode.class)
        void interruptedBeforeStartReadsNothing(ExecutionMode mode) {
            int[] pulls = {0};
            RecordSource counting = () -> {
                pulls[0]++;
                return Optional.empty();
            };
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> engine.run(counting, CATALOG, options(mode, 2, 2, 10), outcome -> { }))
                    .isInstanceOfSatisfying(RunCancelledException.class,
                            e -> assertThat(e.partialSummary().recordsRead()).isZero());
            assertThat(pulls[0]).isZero();
            assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag preserved").isTrue();
        }

        @ParameterizedTest
        @EnumSource(ExecutionMode.class)
        void interruptDuringRunStopsAtTheNextCancellationPoint(ExecutionMode mode) throws InterruptedException {
            AtomicInteger delivered = new AtomicInteger();

            assertThatThrownBy(() -> engine.run(mixedSource(RECORDS), CATALOG, options(mode, 2, 2, 10), outcome -> {
                if (delivered.incrementAndGet() == 1) {
                    Thread.currentThread().interrupt();
                }
            })).isInstanceOfSatisfying(RunCancelledException.class, e -> {
                RunSummary partial = e.partialSummary();
                assertThat(partial.resultCount() + partial.errorCount())
                        .as("partial summary counts exactly what was delivered")
                        .isEqualTo(delivered.get());
                assertThat(partial.recordsRead()).isLessThan(RECORDS);
            });
            assertThat(Thread.interrupted()).isTrue();
            assertNoWorkerThreadsLeft();
        }

        @Test
        void sequentialRunFinishesTheCurrentBatchBeforeStopping() {
            AtomicInteger delivered = new AtomicInteger();

            assertThatThrownBy(() -> engine.run(mixedSource(RECORDS), CATALOG,
                    RunOptions.builder().batchSize(10).build(), outcome -> {
                        if (delivered.incrementAndGet() == 1) {
                            Thread.currentThread().interrupt();
                        }
                    }))
                    .isInstanceOfSatisfying(RunCancelledException.class, e -> {
                        assertThat(e.partialSummary().recordsRead()).isEqualTo(10);
                        assertThat(e.partialSummary().batches()).isEqualTo(1);
                    });
        }
    }
}
