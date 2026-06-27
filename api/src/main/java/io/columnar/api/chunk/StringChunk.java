package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

/**
 * Dictionary-encoded string chunk. {@link #getCode(int)} returns an int code
 * into the column-wide dictionary; {@link #getString(int)} resolves it.
 *
 * <p>Codes enable cheap equality, group-by, and join keys without touching strings.
 */
public interface StringChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.STRING;
    }

    /** Dictionary code for the value at {@code row}. */
    int getCode(int row);

    /** Convenience: resolve {@code row}'s code to a string. May box. */
    String getString(int row);
}
