package io.columnar.api;

/**
 * A single chunk of a single column. Default chunks hold up to ~64K rows but
 * the framework treats {@link #size()} as the source of truth.
 *
 * <p>Chunks expose their {@link #residency() residency} so callers (notably
 * codegen kernels) can pick a fast path keyed on whether the data is on-heap
 * or off-heap.
 *
 * <p>Per-type sub-interfaces ({@code IntChunk}, {@code DoubleChunk}, ...) add
 * primitive accessors and, for the on-heap implementations, direct array access
 * for tight-loop kernels.
 */
public interface ColumnChunk {

    /** Default chunk capacity in rows. */
    int DEFAULT_CAPACITY = 1 << 16; // 65,536

    DataType type();

    /** Number of rows currently populated in this chunk. */
    int size();

    Residency residency();

    /** Per-row null bitmap. Never {@code null}; {@code Validity.allValid(size)} when no nulls. */
    Validity validity();
}
