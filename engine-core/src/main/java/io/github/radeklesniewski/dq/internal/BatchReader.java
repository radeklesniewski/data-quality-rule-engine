package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;

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
    private RuntimeException deferredFailure;

    BatchReader(RecordSource source, int batchSize, SummaryCounter summary) {
        this.source = Objects.requireNonNull(source, "source");
        this.batchSize = batchSize;
        this.summary = summary;
    }

    /**
     * Reads the next batch.
     *
     * <p>If the source fails fatally (any exception other than {@link RecordReadException}) after some records of
     * the batch were read, those records are returned first and the failure is thrown by the <em>next</em> call. So
     * everything read before the break is still validated, whatever the batch size.
     *
     * @return up to {@code batchSize} items, in input order; empty once the source is exhausted
     * @throws RuntimeException the source's fatal failure, once all records read before it were returned
     */
    List<BatchItem> next() {
        throwDeferredFailure();
        if (exhausted) {
            return List.of();
        }
        List<BatchItem> batch = new ArrayList<>(Math.min(batchSize, MAX_INITIAL_CAPACITY));
        while (batch.size() < batchSize && !exhausted && deferredFailure == null) {
            readOne(batch.isEmpty()).ifPresent(batch::add);
        }
        return batch;
    }

    /** Throws a fatal failure held back by the previous call, after which the source counts as exhausted. */
    private void throwDeferredFailure() {
        if (deferredFailure != null) {
            RuntimeException failure = deferredFailure;
            deferredFailure = null;
            exhausted = true;
            throw failure;
        }
    }

    /**
     * Reads one slot: a record, or an unreadable record. Empty at the end of the input, or when a fatal failure was
     * deferred because the batch already holds records that must be validated first.
     */
    private Optional<BatchItem> readOne(boolean batchIsEmpty) {
        Optional<DataRecord> next;
        try {
            next = Objects.requireNonNull(source.next(),
                    "RecordSource.next() must not return null; return Optional.empty() at the end");
        } catch (RecordReadException e) {
            return Optional.of(take(index -> new BatchItem.Unreadable(index, e)));
        } catch (RuntimeException fatal) {
            if (batchIsEmpty) {
                exhausted = true;
                throw fatal;
            }
            deferredFailure = fatal;
            return Optional.empty();
        }
        if (next.isEmpty()) {
            exhausted = true;
            return Optional.empty();
        }
        return Optional.of(take(index -> new BatchItem.Readable(index, next.get())));
    }

    /** Gives the item the next input position and counts it as read. */
    private BatchItem take(LongFunction<BatchItem> item) {
        summary.recordRead();
        return item.apply(nextIndex++);
    }
}
