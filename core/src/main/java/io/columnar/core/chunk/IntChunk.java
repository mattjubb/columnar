package io.columnar.core.chunk;

import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;

/**
 * Column chunk holding {@code int} values. Off-heap implementations live in
 * {@code :memory} and implement this same interface.
 */
public interface IntChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.INT;
    }

    int getInt(int row);
}
