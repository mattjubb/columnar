package io.columnar.core.chunk;

import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;

import java.time.Instant;

/**
 * Chunk whose each row is a fixed-length array of dates stored as epoch nanoseconds
 * in a row-major {@code long[]} ({@link #values()}).
 */
public interface DateArrayChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.DATE_ARRAY;
    }

    /** Number of date elements per row (constant for the column). */
    int elementsPerRow();

    long getEpochNano(int row, int element);

    default Instant getInstant(int row, int element) {
        long nanos = getEpochNano(row, element);
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                Math.floorMod(nanos, 1_000_000_000L));
    }

    /** Row-major packed epoch-nano values; length is at least {@code size() * elementsPerRow()}. */
    long[] values();
}
