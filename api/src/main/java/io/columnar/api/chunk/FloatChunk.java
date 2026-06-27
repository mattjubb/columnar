package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

public interface FloatChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.FLOAT;
    }

    float getFloat(int row);
}
