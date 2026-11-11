package io.github.radeklesniewski.dq;

import static io.github.radeklesniewski.dq.rule.Decision.INVALID;
import static io.github.radeklesniewski.dq.rule.Decision.NOT_APPLICABLE;
import static io.github.radeklesniewski.dq.rule.Decision.VALID;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * F5: a 1,000,000-record run completes with bounded memory in every execution mode.
 *
 * <p>Runs only via {@code ./gradlew :engine-core:memoryTest}, which gives the JVM a 64 MB heap. Three million results
 * held in a list would need several hundred MB, so finishing at all proves the engine doesn't accumulate them.
 * Records are generated lazily and the sink only counts.
 */
@Tag("memory")
class BoundedMemoryTest {

    private static final int RECORDS = 1_000_000;
    private static final long MAX_HEAP_BYTES = 80L * 1024 * 1024;

    @BeforeAll
    static void requireSmallHeap() {
        assertThat(Runtime.getRuntime().maxMemory())
                .as("this test is only meaningful with a small heap; run it via the memoryTest Gradle task")
                .isLessThanOrEqualTo(MAX_HEAP_BYTES);
    }

    /** Cycles through the three fixture records (r1, r2, r3 shapes) with unique ids. */
    private static RecordSource generated(int count) {
        int[] next = {0};
        return () -> {
            int i = next[0]++;
            if (i >= count) {
                return Optional.empty();
            }
            String id = "r" + i;
            return Optional.of(switch (i % 3) {
                case 0 -> DataRecord.ofMap(Map.of("id", id, "vatId", "DE111111111", "country", "DE",
                        "legalName", "ACME GmbH", "iban", "DE123456789"));
                case 1 -> DataRecord.ofMap(Map.of("id", id, "vatId", "FR22", "country", "FR",
                        "legalName", "Bricolage SARL", "iban", "FR123456789"));
                default -> DataRecord.ofMap(Map.of("id", id, "country", "ZZ", "legalName", "Nowhere Ltd",
                        "iban", ""));
            });
        };
    }

    @ParameterizedTest
    @EnumSource(ExecutionMode.class)
    void oneMillionRecordsStreamThroughASmallHeap(ExecutionMode mode) {
        resetPeakHeap();
        long[] emitted = {0};
        long[] lastIndex = {-1};
        boolean[] ordered = {true};
        long start = System.nanoTime();

        RunSummary summary = ValidationEngine.create().run(
                generated(RECORDS),
                RuleCatalog.of(Fixtures.RULES),
                RunOptions.builder().executionMode(mode).batchSize(500).maxInFlightBatches(8).build(),
                outcome -> {
                    emitted[0]++;
                    ordered[0] &= outcome.recordIndex() >= lastIndex[0];
                    lastIndex[0] = outcome.recordIndex();
                });

        long millis = (System.nanoTime() - start) / 1_000_000;
        long n0 = (RECORDS + 2) / 3; // r1-shaped
        long n1 = (RECORDS + 1) / 3; // r2-shaped
        long n2 = RECORDS / 3;       // r3-shaped
        assertThat(summary.recordsRead()).isEqualTo(RECORDS);
        assertThat(summary.resultCount()).isEqualTo(3L * RECORDS).isEqualTo(emitted[0]);
        assertThat(summary.errorCount()).isZero();
        assertThat(summary.count(VALID)).isEqualTo(2 * n0 + n1);
        assertThat(summary.count(INVALID)).isEqualTo(n1 + 3 * n2);
        assertThat(summary.count(NOT_APPLICABLE)).isEqualTo(n0 + n1);
        if (mode != ExecutionMode.PARALLEL_UNORDERED) {
            assertThat(ordered[0]).as("outcomes in input order").isTrue();
        }

        System.out.printf("%-18s %,d records, %,d results in %,d ms (%,d records/s), peak heap %,d MB of %,d MB%n",
                mode, RECORDS, emitted[0], millis, RECORDS * 1000L / Math.max(1, millis),
                peakHeapBytes() / (1024 * 1024), Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    private static void resetPeakHeap() {
        System.gc();
        heapPools().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private static long peakHeapBytes() {
        return heapPools().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
    }

    private static java.util.stream.Stream<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP);
    }
}
