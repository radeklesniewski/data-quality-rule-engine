/**
 * Record access: the host-supplied {@link io.github.radeklesniewski.dq.record.DataRecord} SPI and the
 * read-only {@link io.github.radeklesniewski.dq.record.RecordView} that rule logic uses to read fields.
 *
 * <p>Field semantics used throughout the library:
 * <ul>
 *   <li>Fields are addressed by dot-separated paths, e.g. {@code "address.city"}. Array indexing is not supported.</li>
 *   <li>A JSON {@code null} and a missing field are the same thing: <em>absent</em>.</li>
 *   <li>An empty string is <em>present</em>. Whether it is acceptable is up to the rule.</li>
 * </ul>
 */
package io.github.radeklesniewski.dq.record;
