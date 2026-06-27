package io.columnar.core.chunk;

import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;

/**
 * Chunk whose each row is a fixed-length {@code String[]} stored row-major in {@link #values()}.
 */
public interface StringArrayChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.STRING_ARRAY;
    }

    /** Number of {@code String} elements per row (constant for the column). */
    int elementsPerRow();

    String getString(int row, int element);

    /** Row-major packed values; length is at least {@code size() * elementsPerRow()}. */
    String[] values();
}
