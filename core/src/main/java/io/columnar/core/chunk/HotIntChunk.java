package io.columnar.core.chunk;

import io.columnar.core.Residency;
import io.columnar.core.Validity;

import java.util.Objects;

/** On-heap {@code int[]}-backed implementation of {@link IntChunk}. */
public final class HotIntChunk implements IntChunk {

    private final int[] values;
    private final int size;
    private final Validity validity;

    public HotIntChunk(int[] values, int size, Validity validity) {
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

    /** Build a chunk from a freshly populated array, assuming all values are non-null. */
    public static HotIntChunk of(int[] values) {
        return new HotIntChunk(values, values.length, Validity.allValid(values.length));
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
    public int getInt(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        return values[row];
    }

    /** Direct access to the backing array for tight-loop kernels. Length is {@link #size()}-aligned but may be larger. */
    public int[] values() {
        return values;
    }
}
