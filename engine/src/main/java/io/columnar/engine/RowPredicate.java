package io.columnar.engine;

import io.columnar.core.Column;

import java.util.List;
import java.util.Set;

/**
 * Vectorized chunk predicate. Tests every row in a single chunk and writes
 * the result as a packed bitmap (1 bit per row, set = pass).
 *
 * <p>This is the runtime contract that codegen will eventually emit a
 * specialized implementation of (per expression × column-type signature). The
 * MVP path uses hand-written predicates from {@link RowPredicates}.
 */
public interface RowPredicate {

    /**
     * Columns this predicate reads. Used by {@link FilterOperator} to ensure
     * those columns are pulled from upstream even when the consumer's viewport
     * didn't request them. Implementations must return a non-{@code null} set.
     */
    Set<String> requiredColumns();

    /**
     * Evaluate over rows {@code [0, rowCount)} of the chunk at {@code chunkIdx}
     * across the given columns. Sets bit {@code i} of {@code outBits} when row
     * {@code i} passes; clears it otherwise. {@code outBits} length must be at
     * least {@code (rowCount + 63) / 64}.
     */
    void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits);
}
