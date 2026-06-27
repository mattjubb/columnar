package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

public interface LongChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.LONG;
    }

    long getLong(int row);
}
