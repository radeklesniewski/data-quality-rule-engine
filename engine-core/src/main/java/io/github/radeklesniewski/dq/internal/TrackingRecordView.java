package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.FieldRead;
import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link RecordView} that records each distinct field path read (first read wins, in read order). This is the
 * source of {@code Result} provenance.
 *
 * <p>Not thread-safe. Create one per (rule, record) evaluation.
 */
public final class TrackingRecordView implements RecordView {

    private final DataRecord record;
    private final Map<String, Optional<Object>> reads = new LinkedHashMap<>();

    /**
     * Creates a tracking view over {@code record}.
     *
     * @param record the record to view
     */
    public TrackingRecordView(DataRecord record) {
        this.record = Objects.requireNonNull(record, "record");
    }

    @Override
    public Optional<Object> get(String path) {
        FieldPaths.requireValid(path);
        Optional<Object> value = Objects.requireNonNull(record.get(path),
                () -> "DataRecord.get must not return null (path '" + path + "'); return Optional.empty() instead");
        reads.putIfAbsent(path, value);
        return value;
    }

    /**
     * Returns the fields read so far, in first-read order. Also valid after logic failed part-way.
     *
     * @return the fields read so far, in first-read order
     */
    public List<FieldRead> reads() {
        return reads.entrySet().stream().map(e -> new FieldRead(e.getKey(), e.getValue())).toList();
    }
}
