package io.columnar.core.chunk;

import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;

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
