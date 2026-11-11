package io.github.radeklesniewski.dq.record;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Lazily supplies the records of one run. The engine pulls records only as fast as it evaluates them, so a source
 * backed by a file, a socket or a database cursor keeps memory bounded however large the input is.
 *
 * <p><strong>Errors.</strong> If one record can't be read but the source can carry on (say, an element that isn't
 * a JSON object), throw {@link RecordReadException} from {@link #next()}. The engine reports a {@code RecordError}
 * and calls {@code next()} again. A source that throws this must still advance, or the run never ends. Any other
 * exception means the source itself is broken and aborts the run.
 *
 * <p><strong>Ownership.</strong> The engine doesn't close the source. Whoever opens it closes it, typically with
 * try-with-resources around the run. Not used concurrently: the engine reads from one thread at a time.
 */
@FunctionalInterface
public interface RecordSource extends AutoCloseable {

    /**
     * Returns the next record.
     *
     * @return the next record, or empty once the source is exhausted; never {@code null}
     * @throws RecordReadException if this record is unreadable but later ones may still be read
     */
    Optional<DataRecord> next();

    /** Releases underlying resources. The default does nothing. */
    @Override
    default void close() {
    }

    /**
     * Adapts an in-memory collection.
     *
     * @param records the records; a {@code null} element is reported as unreadable
     * @return a source over {@code records}
     */
    static RecordSource of(Iterable<? extends DataRecord> records) {
        return of(Objects.requireNonNull(records, "records").iterator());
    }

    /**
     * Adapts an iterator. The iterator is consumed lazily.
     *
     * @param records the records; a {@code null} element is reported as unreadable
     * @return a source over {@code records}
     */
    static RecordSource of(Iterator<? extends DataRecord> records) {
        Objects.requireNonNull(records, "records");
        return () -> {
            if (!records.hasNext()) {
                return Optional.empty();
            }
            DataRecord record = records.next();
            if (record == null) {
                throw new RecordReadException("Record source yielded a null record", null);
            }
            return Optional.of(record);
        };
    }

    /**
     * Adapts a stream. The stream is consumed lazily and closed by {@link #close()}.
     *
     * @param records the records; a {@code null} element is reported as unreadable
     * @return a source over {@code records}
     */
    static RecordSource of(Stream<? extends DataRecord> records) {
        Objects.requireNonNull(records, "records");
        RecordSource delegate = of(records.iterator());
        return new RecordSource() {
            @Override
            public Optional<DataRecord> next() {
                return delegate.next();
            }

            @Override
            public void close() {
                records.close();
            }
        };
    }
}
