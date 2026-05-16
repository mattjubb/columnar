package io.columnar.core.chunk;

import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;

public interface DoubleChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.DOUBLE;
    }

    double getDouble(int row);
}
