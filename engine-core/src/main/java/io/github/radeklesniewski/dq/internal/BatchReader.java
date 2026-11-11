package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pulls records from a {@link RecordSource} in batches and assigns their input positions. It only runs on the run's
 * calling thread, and isn't thread-safe. Internal.
 */
final class BatchReader {

    private static final int MAX_INITIAL_CAPACITY = 1_024;

    private final RecordSource source;
    private final int batchSize;
    private final SummaryCounter summary;
    private long nextIndex;
    private boolean exhausted;

    BatchReader(RecordSource source, int batchSize, SummaryCounter summary) {
        this.source = Objects.requireNonNull(source, "source");
        this.batchSize = batchSize;
        this.summary = summary;
    }

    /**
     * Reads the next batch.
     *
     * @return up to {@code batchSize} items, in input order; empty once the source is exhausted
     */
    List<BatchItem> next() {
        if (exhausted) {
            return List.of();
        }
        List<BatchItem> batch = new ArrayList<>(Math.min(batchSize, MAX_INITIAL_CAPACITY));
        while (batch.size() < batchSize) {
            BatchItem item;
            try {
                Optional<DataRecord> next = Objects.requireNonNull(source.next(),
                        "RecordSource.next() must not return null; return Optional.empty() at the end");
                if (next.isEmpty()) {
                    exhausted = true;
                    break;
                }
                item = new BatchItem.Readable(nextIndex, next.get());
            } catch (RecordReadException e) {
                item = new BatchItem.Unreadable(nextIndex, e);
            }
            nextIndex++;
            summary.recordRead();
            batch.add(item);
        }
        return batch;
    }
}
