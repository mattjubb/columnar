package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

/**
 * Bit-packed boolean chunk: 1 bit per value, separate from the validity bitmap.
 * Storage is one {@code long} per 64 rows.
 */
public interface BooleanChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.BOOLEAN;
    }

    boolean getBoolean(int row);
}
