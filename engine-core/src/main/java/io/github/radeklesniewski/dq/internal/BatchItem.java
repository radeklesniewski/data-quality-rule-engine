package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;

/** A slot in a batch: a record, or a record the source couldn't read. Internal. */
sealed interface BatchItem {

    /** Zero-based position in the run's input. */
    long index();

    record Readable(long index, DataRecord record) implements BatchItem {
    }

    record Unreadable(long index, RecordReadException cause) implements BatchItem {
    }
}
