package io.columnar.core.chunk;

import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;

public interface LongChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.LONG;
    }

    long getLong(int row);
}
