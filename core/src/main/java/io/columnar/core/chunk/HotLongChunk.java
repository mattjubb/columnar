package io.columnar.core.chunk;

import io.columnar.core.Residency;
import io.columnar.core.Validity;

import java.util.Objects;

public final class HotLongChunk implements LongChunk {

    private final long[] values;
    private final int size;
    private final Validity validity;

    public HotLongChunk(long[] values, int size, Validity validity) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(validity, "validity");
        if (size < 0 || size > values.length) {
            throw new IllegalArgumentException("size=" + size + " values.length=" + values.length);
        }
        if (validity.size() != size) {
            throw new IllegalArgumentException(
                    "validity.size=" + validity.size() + " != size=" + size);
        }
        this.values = values;
        this.size = size;
        this.validity = validity;
    }

    public static HotLongChunk of(long[] values) {
        return new HotLongChunk(values, values.length, Validity.allValid(values.length));
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Residency residency() {
        return Residency.HOT;
    }

    @Override
    public Validity validity() {
        return validity;
    }

    @Override
    public long getLong(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        return values[row];
    }

    public long[] values() {
        return values;
    }
}
