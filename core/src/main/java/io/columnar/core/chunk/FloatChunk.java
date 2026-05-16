package io.columnar.core.chunk;

import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;

public interface FloatChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.FLOAT;
    }

    float getFloat(int row);
}
