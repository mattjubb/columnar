package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

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
