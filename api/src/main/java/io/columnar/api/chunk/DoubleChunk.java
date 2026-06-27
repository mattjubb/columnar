package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

public interface DoubleChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.DOUBLE;
    }

    double getDouble(int row);
}
