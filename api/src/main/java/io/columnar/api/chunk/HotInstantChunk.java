package io.columnar.api.chunk;

import io.columnar.api.Residency;
import io.columnar.api.Validity;

import java.util.Objects;

public final class HotInstantChunk implements InstantChunk {

    private final long[] epochNanos;
    private final int size;
    private final Validity validity;

    public HotInstantChunk(long[] epochNanos, int size, Validity validity) {
        Objects.requireNonNull(epochNanos, "epochNanos");
        Objects.requireNonNull(validity, "validity");
        if (size < 0 || size > epochNanos.length) {
            throw new IllegalArgumentException(
                    "size=" + size + " epochNanos.length=" + epochNanos.length);
        }
        if (validity.size() != size) {
            throw new IllegalArgumentException(
                    "validity.size=" + validity.size() + " != size=" + size);
        }
        this.epochNanos = epochNanos;
        this.size = size;
        this.validity = validity;
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
    public long getEpochNano(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        return epochNanos[row];
    }

    public long[] epochNanos() {
        return epochNanos;
    }
}
