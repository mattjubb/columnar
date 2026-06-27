package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

/**
 * Chunk whose each row is a fixed-length {@code double[]} stored row-major in {@link #values()}.
 */
public interface DoubleArrayChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.DOUBLE_ARRAY;
    }

    /** Number of {@code double} elements per row (constant for the column). */
    int elementsPerRow();

    double getDouble(int row, int element);

    /** Row-major packed values; length is at least {@code size() * elementsPerRow()}. */
    double[] values();
}
