package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

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
