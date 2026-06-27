package io.columnar.core.chunk;

import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;

/**
 * Chunk whose each row is a fixed-length {@code int[]} stored row-major in {@link #values()}.
 */
public interface IntArrayChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.INT_ARRAY;
    }

    /** Number of {@code int} elements per row (constant for the column). */
    int elementsPerRow();

    int getInt(int row, int element);

    /** Row-major packed values; length is at least {@code size() * elementsPerRow()}. */
    int[] values();
}
