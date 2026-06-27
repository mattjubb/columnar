package io.columnar.api.chunk;

import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

import java.time.Instant;

/**
 * Timestamp chunk stored as {@code long} epoch nanoseconds. Avoids
 * {@link Instant} boxing in the hot path.
 */
public interface InstantChunk extends ColumnChunk {

    @Override
    default DataType type() {
        return DataType.INSTANT;
    }

    long getEpochNano(int row);

    default Instant getInstant(int row) {
        long nanos = getEpochNano(row);
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                Math.floorMod(nanos, 1_000_000_000L));
    }
}
